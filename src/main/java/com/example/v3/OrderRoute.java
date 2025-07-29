package com.example.v3;

import com.example.OrderEntity;
import com.example.OrderModel;
import com.example.StatusEnum;
import com.github.javafaker.Faker;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

//@Component
public class OrderRoute extends RouteBuilder {

    @Override
    public void configure() {

        // Produce data
        Faker faker = new Faker(new Locale("en-NZ"));
        from("timer:orderProducer?period={{timer.period}}").autoStartup(true).routeId("orderProducer")
                .process(exchange -> {
                    String[] possibleStatus = {"APPROVED", "CANCELLED", "DONE", "DRAFT"};

                    int randomCount = faker.random().nextInt(5000, 10000);
                    List<String> randomLengthList = new ArrayList<>();
                    for (int i = 0; i < randomCount; i++) {
                        randomLengthList.add(faker.idNumber().valid());
                    }
                    OrderModel orderEvent = OrderModel.builder()
                            .id((faker.number().numberBetween(0, 5000)))
                            .version(faker.number().numberBetween(0, 5))
                            .name(faker.lorem().word())
                            .status(StatusEnum.valueOf(faker.options().option(possibleStatus)))
                            .effectiveDate(OffsetDateTime.now().plusMinutes(faker.number().numberBetween(2, 58)))
                            .description(faker.lorem().paragraph(2))
                            .build();
                    exchange.getMessage().setBody(orderEvent);
                    exchange.getMessage().setHeader(KafkaConstants.KEY, orderEvent.getId());
                })

                .marshal().json(JsonLibrary.Jackson)
                .log("Body before sending to Kafka ${body}")
                .to("kafka:order-demo?"
                        + "brokers=localhost:9092"
                        + "&valueSerializer=org.apache.kafka.common.serialization.StringSerializer"
                        + "&keySerializer=org.apache.kafka.common.serialization.StringSerializer");

        // Consume data
        from("kafka:order-demo?brokers=localhost:9092&groupId=my-group")
                .log("got a body ${body}")
                .unmarshal().json(JsonLibrary.Jackson, OrderModel.class)
                .to("bean-validator://ValidateModel")
                .process(exchange -> {
                    OrderModel input = exchange.getMessage().getBody(OrderModel.class);
                    OrderEntity output = OrderEntity.builder()
                            .id(input.getId())
                            .name(input.getName())
                            .description(input.getDescription())
                            .effectiveDate(input.getEffectiveDate().toLocalDate())
                            .status(input.getStatus().name()).build();

                    exchange.getMessage().setBody(output);

                })
                .log("got a body after ${body}")
                .to("direct:upsertOrder");

        // SELECT all pending orders
        from("timer:fetchOrders?repeatCount=1").autoStartup(false)
            .to("sql:SELECT * FROM [Orders] WHERE status = 'PENDING'?outputClass=com.example.OrderEntity")
            .log("Fetched Order: ${body}");

        // UPSERT order - check if exists and needs update, then insert or update accordingly
        from("direct:upsertOrder").autoStartup(true)
            .process(exchange -> {
                OrderEntity newOrder = exchange.getIn().getBody(OrderEntity.class);
                Map<String, Object> params = new HashMap<>();
                params.put("id", newOrder.getId());
                exchange.getIn().setBody(params);
                exchange.setProperty("newOrder", newOrder);
            })
            // First, check if record exists
            .to("sql:SELECT id, name, description, effective_date, status FROM [Orders] WHERE id = :#id?outputClass=com.example.OrderEntity")
            .choice()
                .when(simple("${body.size()} == 0"))
                    // Record doesn't exist - INSERT
                    .log("Record with ID ${exchangeProperty.newOrder.id} not found, inserting new record")
                    .to("direct:insertNewOrder")
                .otherwise()
                    // Record exists - check if update is needed
                    .log("Record with ID ${exchangeProperty.newOrder.id} found, checking if update needed")
                    .to("direct:checkAndUpdateOrder")
            .end();

        // Insert new order
        from("direct:insertNewOrder").autoStartup(true)
            .process(exchange -> {
                OrderEntity order = exchange.getProperty("newOrder", OrderEntity.class);
                Map<String, Object> params = new HashMap<>();
                params.put("id", order.getId());
                params.put("name", order.getName());
                params.put("description", order.getDescription());
                params.put("effectiveDate", order.getEffectiveDate());
                params.put("status", order.getStatus());
                exchange.getIn().setBody(params);
            })
            .to("sql:INSERT INTO [Orders](id, name, description, effective_date, status) " +
                "VALUES (:#id, :#name, :#description, :#effectiveDate, :#status)")
            .log("Inserted new order with ID: ${exchangeProperty.newOrder.id}");

        // Check if update is needed and perform update
        from("direct:checkAndUpdateOrder").autoStartup(true)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                List<OrderEntity> existingOrders = exchange.getIn().getBody(List.class);
                OrderEntity existingOrder = existingOrders.get(0);
                OrderEntity newOrder = exchange.getProperty("newOrder", OrderEntity.class);
                
                boolean needsUpdate = false;
                
                // Check if any of the specified fields have changed
                if (!Objects.equals(existingOrder.getName(), newOrder.getName())) {
                    needsUpdate = true;
                    exchange.getIn().setHeader("nameChanged", true);
                }
                
                if (!Objects.equals(existingOrder.getDescription(), newOrder.getDescription())) {
                    needsUpdate = true;
                    exchange.getIn().setHeader("descriptionChanged", true);
                }
                
                if (!Objects.equals(existingOrder.getStatus(), newOrder.getStatus())) {
                    needsUpdate = true;
                    exchange.getIn().setHeader("statusChanged", true);
                }
                
                if (!Objects.equals(existingOrder.getEffectiveDate(), newOrder.getEffectiveDate())) {
                    needsUpdate = true;
                    exchange.getIn().setHeader("effectiveDateChanged", true);
                }
                
                exchange.getIn().setHeader("needsUpdate", needsUpdate);
                
                if (needsUpdate) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("id", newOrder.getId());
                    params.put("name", newOrder.getName());
                    params.put("description", newOrder.getDescription());
                    params.put("effectiveDate", newOrder.getEffectiveDate());
                    params.put("status", newOrder.getStatus());
                    exchange.getIn().setBody(params);
                }
            })
            .choice()
                .when(header("needsUpdate").isEqualTo(true))
                    .log("Updating order with ID: ${exchangeProperty.newOrder.id} - Changes detected")
                    .to("sql:UPDATE [Orders] SET name = :#name, description = :#description, " +
                        "effective_date = :#effectiveDate, status = :#status WHERE id = :#id")
                    .log("Successfully updated order with ID: ${exchangeProperty.newOrder.id}")
                .otherwise()
                    .log("No changes detected for order with ID: ${exchangeProperty.newOrder.id}, skipping update")
            .end();
    }
}