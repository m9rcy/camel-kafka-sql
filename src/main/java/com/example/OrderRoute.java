package com.example;

import com.github.javafaker.Faker;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
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

        // INSERT sample order (you can call this from another route or REST endpoint)
        from("direct:insertOrder").autoStartup(true)
            .process(exchange -> {
                OrderEntity order = exchange.getIn().getBody(OrderEntity.class);
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
            .log("Inserted new order: ${body}");

        from("direct:upsertOrder").autoStartup(true)
                .process(exchange -> {
                    OrderEntity order = exchange.getIn().getBody(OrderEntity.class);
                    Map<String, Object> params = new HashMap<>();
                    params.put("id", order.getId());
                    params.put("name", order.getName());
                    params.put("description", order.getDescription());
                    params.put("effectiveDate", order.getEffectiveDate());
                    params.put("status", order.getStatus());
                    exchange.getIn().setBody(params);
                })
                .to("sql:MERGE [Orders] AS target " +
                        "USING (SELECT :#id as id, :#name as name, :#description as description, " +
                        ":#effectiveDate as effective_date, :#status as status) AS source " +
                        "ON target.id = source.id " +
                        "WHEN MATCHED AND (target.name != source.name OR target.description != source.description OR " +
                        "target.effective_date != source.effective_date OR target.status != source.status) THEN " +
                        "UPDATE SET name = source.name, description = source.description, " +
                        "effective_date = source.effective_date, status = source.status " +
                        "WHEN NOT MATCHED THEN " +
                        "INSERT (id, name, description, effective_date, status) " +
                        "VALUES (source.id, source.name, source.description, source.effective_date, source.status);")
                .log("Upsert completed for order ID: ${body}");
    }
}
