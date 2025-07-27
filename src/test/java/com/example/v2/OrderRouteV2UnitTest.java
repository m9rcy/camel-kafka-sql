package com.example.v2;

import com.example.OrderEntity;
import com.example.OrderModel;
import com.example.StatusEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.camel.test.spring.junit5.UseAdviceWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@CamelSpringBootTest
@UseAdviceWith
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "timer.period=10000", // Slow down timer for testing
    "camel.springboot.main-run-controller=false"
})
class OrderRouteV2UnitTest {

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private RouteBuilder OrderRoute;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void testOrderProducerRoute() throws Exception {
        // Arrange
        MockEndpoint mockKafka = camelContext.getEndpoint("mock:kafka", MockEndpoint.class);
        
        AdviceWith.adviceWith(camelContext, "orderProducerV2", r -> {
            r.replaceFromWith("direct:startProducer");
            r.mockEndpoints("kafka:*");
        });
        
        camelContext.start();
        
        // Act
        mockKafka.expectedMessageCount(1);
        mockKafka.expectedHeaderReceived("orderVersion", 1);
        
        producerTemplate.sendBody("direct:startProducer", null);
        
        // Assert
        mockKafka.assertIsSatisfied();
        
        List<Exchange> exchanges = mockKafka.getReceivedExchanges();
        Exchange exchange = exchanges.get(0);
        
        String jsonBody = exchange.getIn().getBody(String.class);
        assertNotNull(jsonBody);
        
        OrderModel order = objectMapper.readValue(jsonBody, OrderModel.class);
        assertNotNull(order);
        assertTrue(order.getId() > 0);
        assertNotNull(order.getName());
        assertNotNull(order.getStatus());
        assertEquals(1, order.getVersion());
    }

    @Test
    void testOrderConsumerRoute() throws Exception {
        // Arrange
        MockEndpoint mockUpsert = camelContext.getEndpoint("mock:direct:upsertOrderV2", MockEndpoint.class);
        
        AdviceWith.adviceWith(camelContext, "orderConsumerV2", r -> {
            r.replaceFromWith("direct:startConsumer");
            r.mockEndpoints("direct:upsertOrderV2");
        });
        
        camelContext.start();
        
        OrderModel testOrder = OrderModel.builder()
            .id(123)
            .version(1)
            .name("Test Order")
            .description("Test Description")
            .effectiveDate(OffsetDateTime.now())
            .status(StatusEnum.DRAFT)
            .build();
        
        String jsonOrder = objectMapper.writeValueAsString(testOrder);
        
        // Act
        mockUpsert.expectedMessageCount(1);
        mockUpsert.expectedHeaderReceived("originalVersion", 1);
        
        producerTemplate.sendBody("direct:startConsumer", jsonOrder);
        
        // Assert
        mockUpsert.assertIsSatisfied();
        
        Exchange exchange = mockUpsert.getReceivedExchanges().get(0);
        OrderEntity entity = exchange.getIn().getBody(OrderEntity.class);
        
        assertNotNull(entity);
        assertEquals(123, entity.getId());
        assertEquals("Test Order", entity.getName());
        assertEquals("Test Description", entity.getDescription());
        assertEquals("DRAFT", entity.getStatus());
        assertNotNull(entity.getEffectiveDate());
    }

    @Test
    void testOrderConsumerWithInvalidData() throws Exception {
        // Arrange
        MockEndpoint mockDeadLetter = camelContext.getEndpoint("mock:direct:deadLetter", MockEndpoint.class);
        
        AdviceWith.adviceWith(camelContext, "orderConsumerV2", r -> {
            r.replaceFromWith("direct:startConsumer");
            r.mockEndpoints("direct:*");
        });
        
        camelContext.start();
        
        OrderModel invalidOrder = OrderModel.builder()
            .id(-1) // Invalid ID
            .version(1)
            .name("") // Empty name
            .status(StatusEnum.DRAFT)
            .build();
        
        String jsonOrder = objectMapper.writeValueAsString(invalidOrder);
        
        // Act
        mockDeadLetter.expectedMessageCount(1);
        
        producerTemplate.sendBody("direct:startConsumer", jsonOrder);
        
        // Assert
        mockDeadLetter.assertIsSatisfied();
    }

    @Test
    void testUpsertRouteChoiceForNewOrder() throws Exception {
        // Arrange
        MockEndpoint mockInsert = camelContext.getEndpoint("mock:direct:insertOrderV2", MockEndpoint.class);
        MockEndpoint mockUpdate = camelContext.getEndpoint("mock:direct:updateOrderV2", MockEndpoint.class);
        
        AdviceWith.adviceWith(camelContext, "upsertOrderV2", r -> {
            r.replaceFromWith("direct:startUpsert");
            r.mockEndpoints("direct:insertOrderV2");
            r.mockEndpoints("direct:updateOrderV2");
        });
        
        camelContext.start();
        
        OrderEntity testEntity = OrderEntity.builder()
            .id(123)
            .name("Test Order")
            .status("DRAFT")
            .build();
        
        // Act - Test new order (version 1)
        mockInsert.expectedMessageCount(1);
        mockUpdate.expectedMessageCount(0);
        
        producerTemplate.sendBodyAndHeader("direct:startUpsert", testEntity, "originalVersion", 1);
        
        // Assert
        mockInsert.assertIsSatisfied();
        mockUpdate.assertIsSatisfied();
    }

    @Test
    void testUpsertRouteChoiceForExistingOrder() throws Exception {
        // Arrange
        MockEndpoint mockInsert = camelContext.getEndpoint("mock:direct:insertOrderV2", MockEndpoint.class);
        MockEndpoint mockUpdate = camelContext.getEndpoint("mock:direct:updateOrderV2", MockEndpoint.class);
        
        AdviceWith.adviceWith(camelContext, "upsertOrderV2", r -> {
            r.replaceFromWith("direct:startUpsert");
            r.mockEndpoints("direct:insertOrderV2");
            r.mockEndpoints("direct:updateOrderV2");
        });
        
        camelContext.start();
        
        OrderEntity testEntity = OrderEntity.builder()
            .id(123)
            .name("Test Order")
            .status("DRAFT")
            .build();
        
        // Act - Test existing order (version > 1)
        mockInsert.expectedMessageCount(0);
        mockUpdate.expectedMessageCount(1);
        
        producerTemplate.sendBodyAndHeader("direct:startUpsert", testEntity, "originalVersion", 2);
        
        // Assert
        mockInsert.assertIsSatisfied();
        mockUpdate.assertIsSatisfied();
    }

    @Test
    void testDataTransformation() throws Exception {
        // Arrange
        MockEndpoint mockResult = camelContext.getEndpoint("mock:result", MockEndpoint.class);
        
        AdviceWith.adviceWith(camelContext, "orderConsumerV2", r -> {
            r.replaceFromWith("direct:testTransform");
            r.weaveAddLast().to("mock:result");
        });
        
        camelContext.start();
        
        OrderModel testOrder = OrderModel.builder()
            .id(456)
            .version(2)
            .name("  Trimmed Order  ") // Test trimming
            .description("Test Description")
            .effectiveDate(OffsetDateTime.now())
            .status(StatusEnum.APPROVED)
            .build();
        
        String jsonOrder = objectMapper.writeValueAsString(testOrder);
        
        // Act
        mockResult.expectedMessageCount(1);
        
        producerTemplate.sendBody("direct:testTransform", jsonOrder);
        
        // Assert
        mockResult.assertIsSatisfied();
        
        Exchange exchange = mockResult.getReceivedExchanges().get(0);
        OrderEntity entity = exchange.getIn().getBody(OrderEntity.class);
        
        assertEquals("Trimmed Order", entity.getName()); // Should be trimmed
        assertEquals("APPROVED", entity.getStatus());
        assertEquals(2, exchange.getIn().getHeader("originalVersion"));
    }
}