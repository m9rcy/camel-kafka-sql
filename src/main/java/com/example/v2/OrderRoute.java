package com.example.v2;

import com.example.OrderEntity;
import com.example.OrderModel;
import com.example.StatusEnum;
import com.github.javafaker.Faker;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

//@Component
public class OrderRoute extends RouteBuilder {

    // SQL constants for better readability
    private static final String MERGE_SQL = 
            "MERGE [Order] AS target " +
            "USING (" +
                "SELECT :#id as id, " +
                       ":#name as name, " +
                       ":#description as description, " +
                       ":#effectiveDate as effective_date, " +
                       ":#status as status" +
            ") AS source " +
            "ON target.id = source.id " +
            "WHEN MATCHED AND (" +
                "target.name != source.name OR " +
                "target.description != source.description OR " +
                "target.effective_date != source.effective_date OR " +
                "target.status != source.status" +
            ") THEN " +
                "UPDATE SET " +
                    "name = source.name, " +
                    "description = source.description, " +
                    "effective_date = source.effective_date, " +
                    "status = source.status " +
            "WHEN NOT MATCHED THEN " +
                "INSERT (id, name, description, effective_date, status) " +
                "VALUES (source.id, source.name, source.description, source.effective_date, source.status);";

    private static final String SELECT_PENDING_ORDERS = 
            "SELECT * FROM [Order] WHERE status = 'PENDING'?outputClass=com.example.OrderEntity";

    @Override
    public void configure() {

        // Produce data
        Faker faker = new Faker(new Locale("en-NZ"));
        
        from("timer:orderProducer?period={{timer.period}}")
                .autoStartup(true)
                .routeId("orderProducer")
                .process(this::createRandomOrder)
                .marshal().json(JsonLibrary.Jackson)
                .log("Body before sending to Kafka ${body}")
                .to("kafka:order-demo?"
                        + "brokers=localhost:9092"
                        + "&valueSerializer=org.apache.kafka.common.serialization.StringSerializer"
                        + "&keySerializer=org.apache.kafka.common.serialization.StringSerializer");

        // Consume data and process upsert
        from("kafka:order-demo?brokers=localhost:9092&groupId=my-group")
                .log("Received message from Kafka: ${body}")
                .unmarshal().json(JsonLibrary.Jackson, OrderModel.class)
                .to("bean-validator://ValidateModel")
                .process(this::transformOrderModelToEntity)
                .log("Transformed to entity: ${body}")
                .to("direct:upsertOrder");

        // SELECT all pending orders
        from("timer:fetchOrders?repeatCount=1")
                .autoStartup(false)
                .to("sql:" + SELECT_PENDING_ORDERS)
                .log("Fetched pending orders: ${body}");

        // UPSERT order using MERGE statement
        from("direct:upsertOrder")
                .autoStartup(true)
                .process(this::prepareUpsertParameters)
                .to("sql:" + MERGE_SQL)
                .log("Upsert operation completed for order ID: ${exchangeProperty.orderId}");
    }

    /**
     * Creates a random order for testing purposes
     */
    private void createRandomOrder(org.apache.camel.Exchange exchange) {
        Faker faker = new Faker(new Locale("en-NZ"));
        String[] possibleStatus = {"APPROVED", "CANCELLED", "DONE", "DRAFT"};

        OrderModel orderEvent = OrderModel.builder()
                .id(faker.number().numberBetween(1, 5000))
                .version(faker.number().numberBetween(1, 5))
                .name(faker.lorem().word())
                .status(StatusEnum.valueOf(faker.options().option(possibleStatus)))
                .effectiveDate(OffsetDateTime.now().plusMinutes(faker.number().numberBetween(2, 58)))
                .description(faker.lorem().paragraph(2))
                .build();

        exchange.getMessage().setBody(orderEvent);
        exchange.getMessage().setHeader(KafkaConstants.KEY, orderEvent.getId());
    }

    /**
     * Transforms OrderModel to OrderEntity
     */
    private void transformOrderModelToEntity(org.apache.camel.Exchange exchange) {
        OrderModel input = exchange.getMessage().getBody(OrderModel.class);
        
        OrderEntity output = OrderEntity.builder()
                .id(input.getId())
                .name(input.getName())
                .description(input.getDescription())
                .effectiveDate(input.getEffectiveDate().toLocalDate())
                .status(input.getStatus().name())
                .build();

        exchange.getMessage().setBody(output);
    }

    /**
     * Prepares parameters for the MERGE SQL statement
     */
    private void prepareUpsertParameters(org.apache.camel.Exchange exchange) {
        OrderEntity order = exchange.getIn().getBody(OrderEntity.class);
        
        // Store order ID for logging
        exchange.setProperty("orderId", order.getId());
        
        // Prepare SQL parameters
        Map<String, Object> params = new HashMap<>();
        params.put("id", order.getId());
        params.put("name", order.getName());
        params.put("description", order.getDescription());
        params.put("effectiveDate", order.getEffectiveDate());
        params.put("status", order.getStatus());
        
        exchange.getIn().setBody(params);
    }
}