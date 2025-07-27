package com.example.v2;

import com.example.OrderModel;
import com.example.StatusEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@CamelSpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"order-demo"},
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:9095",
                "port=9095"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:9095",
        "kafka.brokers=localhost:9095",
        "kafka.groupId=simple-testcontainers-group",
        "timer.period=60000",
        "camel.springboot.main-run-controller=false",
        "spring.sql.init.mode=never"
})
class SimpleTestContainersIntegrationTest {

    @Container
    static MSSQLServerContainer<?> mssqlContainer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense()
            .withPassword("YourStrong!Passw0rd");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mssqlContainer.getJdbcUrl());
        registry.add("spring.datasource.username", mssqlContainer::getUsername);
        registry.add("spring.datasource.password", mssqlContainer::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;

    @BeforeAll
    static void initializeDatabase() {
        // Create database and table using direct JDBC connection
        try (Connection conn = DriverManager.getConnection(
                mssqlContainer.getJdbcUrl(),
                mssqlContainer.getUsername(),
                mssqlContainer.getPassword());
             Statement stmt = conn.createStatement()) {

            // Create database
            stmt.execute("CREATE DATABASE CamelDemo");

            // Switch to the new database
            stmt.execute("USE CamelDemo");

            // Create table
            stmt.execute("CREATE TABLE [Order] (\n" +
                         "    id INT PRIMARY KEY,\n" +
                         "    name NVARCHAR(255) NOT NULL,\n" +
                         "    description NVARCHAR(1000),\n" +
                         "    effective_date DATE,\n" +
                         "    status NVARCHAR(50),\n" +
                         "    created_date DATETIME2 DEFAULT GETDATE(),\n" +
                         "    updated_date DATETIME2 DEFAULT GETDATE()\n" +
                         ")\n");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Switch to CamelDemo database and clean up data
        try {
            jdbcTemplate.execute("USE CamelDemo");
            jdbcTemplate.execute("DELETE FROM [Order]");
        } catch (Exception e) {
            // Handle any cleanup errors
        }

        // Ensure Camel context is started
        if (!camelContext.isStarted()) {
            try {
                camelContext.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start Camel context", e);
            }
        }
    }

    @Test
    void testOrderFlowWithMSSQLServerContainer() throws Exception {
        // Arrange
        OrderModel testOrder = OrderModel.builder()
                .id(1001)
                .version(1)
                .name("TestContainers Order")
                .description("Testing with real MS SQL Server")
                .effectiveDate(OffsetDateTime.now().plusDays(1))
                .status(StatusEnum.DRAFT)
                .build();

        String jsonOrder = objectMapper.writeValueAsString(testOrder);

        // Act - Send message to Kafka
        kafkaTemplate.send("order-demo", String.valueOf(testOrder.getId()), jsonOrder);

        // Assert - Wait for message to be processed and stored
        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                            "SELECT * FROM [Order] WHERE id = ?", testOrder.getId());

                    assertFalse(orders.isEmpty(), "Order should be inserted into MS SQL Server");

                    Map<String, Object> order = orders.get(0);
                    assertEquals(testOrder.getId(), order.get("id"));
                    assertEquals("TestContainers Order", order.get("name"));
                    assertEquals("Testing with real MS SQL Server", order.get("description"));
                    assertEquals("DRAFT", order.get("status"));
                    assertNotNull(order.get("effective_date"));
                });
    }

    @Test
    void testDirectDatabaseOperations() throws Exception {
        // Test direct database operations to ensure container is working

        // Insert using JdbcTemplate
        int result = jdbcTemplate.update(
                "INSERT INTO [Order] (id, name, description, effective_date, status) VALUES (?, ?, ?, ?, ?)",
                2001, "Direct Insert", "Direct database insert test",
                java.sql.Date.valueOf("2025-07-25"), "PENDING"
        );

        assertEquals(1, result, "Should insert one record");

        // Query the data back
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT * FROM [Order] WHERE id = ?", 2001);

        assertFalse(orders.isEmpty());
        Map<String, Object> order = orders.get(0);
        assertEquals("Direct Insert", order.get("name"));
        assertEquals("PENDING", order.get("status"));
    }

    @Test
    void testMSSQLServerFeatures() throws Exception {
        // Test MS SQL Server specific features

        // Test DATETIME2 precision
        jdbcTemplate.update("INSERT INTO [Order] (id, name, description, effective_date, status, created_date)\n" +
                            "VALUES (?, ?, ?, ?, ?, SYSDATETIME())\n",
                3001, "Precision Test", "Testing DATETIME2 precision",
                java.sql.Date.valueOf("2025-07-25"), "ACTIVE"
        );

        // Test NVARCHAR with Unicode
        jdbcTemplate.update(
                "INSERT INTO [Order] (id, name, description, effective_date, status) VALUES (?, ?, ?, ?, ?)",
                3002, "Unicode Test: 测试", "Description: àáâãäåæçèéêë",
                java.sql.Date.valueOf("2025-07-25"), "UNICODE"
        );

        // Verify both records
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT * FROM [Order] WHERE id IN (?, ?) ORDER BY id", 3001, 3002);

        assertEquals(2, orders.size());

        // Check precision test
        Map<String, Object> precisionOrder = orders.get(0);
        assertEquals("Precision Test", precisionOrder.get("name"));
        assertNotNull(precisionOrder.get("created_date"));

        // Check Unicode test
        Map<String, Object> unicodeOrder = orders.get(1);
        assertEquals("Unicode Test: 测试", unicodeOrder.get("name"));
        assertEquals("Description: àáâãäåæçèéêë", unicodeOrder.get("description"));
    }

    @Test
    void testContainerHealth() {
        // Verify container is healthy and database is accessible
        assertTrue(mssqlContainer.isRunning(), "Container should be running");
        assertTrue(mssqlContainer.isHealthy(), "Container should be healthy");

        // Test basic connectivity
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM [Order]", Long.class);
        assertNotNull(count);
        assertEquals(0L, count); // Should be 0 after cleanup
    }
}