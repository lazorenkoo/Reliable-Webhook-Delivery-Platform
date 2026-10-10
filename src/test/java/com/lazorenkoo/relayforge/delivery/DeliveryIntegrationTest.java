package com.lazorenkoo.relayforge.delivery;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.lazorenkoo.relayforge.endpoint.EndpointRepository;
import com.lazorenkoo.relayforge.event.EventRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///delivery_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DeliveryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private HttpClient client;
    private HttpServer receiver;
    private AtomicInteger receiverStatus;
    private AtomicReference<String> receivedPayload;

    @BeforeEach
    void setUp() throws Exception {
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        endpointRepository.deleteAll();
        client = HttpClient.newHttpClient();
        receiverStatus = new AtomicInteger(200);
        receivedPayload = new AtomicReference<>();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/webhook", exchange -> {
            try (exchange) {
                receivedPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(receiverStatus.get(), -1);
            }
        });
        receiver.start();
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
        client.close();
    }

    @Test
    void createsEventSendsWebhookAndReadsHistory() throws Exception {
        String payload = jsonMapper.writeValueAsString(Map.of("message", "Привет".repeat(100)));
        long endpointId = createEndpoint();
        long eventId = createEvent(payload);

        HttpResponse<String> response = send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId));

        assertEquals(200, response.statusCode());
        JsonNode result = jsonMapper.readTree(response.body());
        assertEquals(200, result.get("httpStatus").asInt());
        assertTrue(result.get("successful").asBoolean());
        assertEquals(payload, receivedPayload.get());

        Delivery delivery = deliveryRepository.findAll().getFirst();
        assertEquals(eventId, delivery.getEventId());
        assertEquals(endpointId, delivery.getEndpointId());
        assertEquals(DeliveryStatus.SUCCEEDED, delivery.getStatus());
        assertEquals(200, delivery.getHttpStatus());
        assertNull(delivery.getLastError());
        assertNotNull(delivery.getCreatedAt());
        assertNotNull(delivery.getCompletedAt());
        assertFalse(delivery.getCompletedAt().isBefore(delivery.getCreatedAt()));
        assertNotNull(eventRepository.findById(eventId).orElseThrow().getCreatedAt());

        HttpResponse<String> details = send("GET", "/api/v1/deliveries/" + delivery.getId(), null);
        assertEquals(200, details.statusCode());
        JsonNode detailBody = jsonMapper.readTree(details.body());
        assertEquals("SUCCEEDED", detailBody.get("status").asText());
        assertEquals(200, detailBody.get("httpStatus").asInt());
        assertFalse(detailBody.get("createdAt").isNull());
        assertFalse(detailBody.get("completedAt").isNull());

        HttpResponse<String> history = send("GET", "/api/v1/deliveries", null);
        assertEquals(200, history.statusCode());
        assertEquals(1, jsonMapper.readTree(history.body()).size());
    }

    @Test
    void storesReceiverErrorInDatabase() throws Exception {
        receiverStatus.set(500);
        long endpointId = createEndpoint();
        long eventId = createEvent("{\"orderId\":123}");

        HttpResponse<String> response = send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId));

        assertEquals(200, response.statusCode());
        assertFalse(jsonMapper.readTree(response.body()).get("successful").asBoolean());
        Delivery delivery = deliveryRepository.findAll().getFirst();
        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertEquals(500, delivery.getHttpStatus());
        assertEquals("Receiver returned HTTP 500", delivery.getLastError());
        assertNotNull(delivery.getCompletedAt());
    }

    @Test
    void storesNetworkFailureWithoutHttpStatus() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        receiver.stop(0);

        HttpResponse<String> response = send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId));

        assertEquals(502, response.statusCode());
        Delivery delivery = deliveryRepository.findAll().getFirst();
        assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
        assertNull(delivery.getHttpStatus());
        assertNotNull(delivery.getLastError());
        assertFalse(delivery.getLastError().isBlank());
        assertNotNull(delivery.getCompletedAt());
    }

    @Test
    void rejectsDisabledEndpointWithoutCreatingHistory() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        assertEquals(200, send("PATCH", "/api/v1/endpoints/" + endpointId + "/disable", null).statusCode());

        HttpResponse<String> response = send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId));

        assertEquals(400, response.statusCode());
        assertEquals(0, deliveryRepository.count());
        assertNull(receivedPayload.get());
    }

    @Test
    void rejectsInvalidInputAndMissingRecords() throws Exception {
        assertEquals(400, send("POST", "/api/v1/endpoints", Map.of("url", "ftp://example.com")).statusCode());
        assertEquals(400, send("POST", "/api/v1/events", Map.of("type", " ", "payload", "{}")).statusCode());
        assertEquals(400, send("POST", "/api/v1/deliveries", Map.of("eventId", 0, "endpointId", 1)).statusCode());
        assertEquals(404, send("GET", "/api/v1/deliveries/999999", null).statusCode());
        assertEquals(404, send("POST", "/api/v1/deliveries", Map.of("eventId", 999999, "endpointId", 999999)).statusCode());
        assertEquals(0, endpointRepository.count());
        assertEquals(0, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
    }

    @Test
    void filtersHistoryByStatus() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        Map<String, Long> request = Map.of("eventId", eventId, "endpointId", endpointId);
        assertEquals(200, send("POST", "/api/v1/deliveries", request).statusCode());
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries", request).statusCode());

        HttpResponse<String> failed = send("GET", "/api/v1/deliveries?status=FAILED", null);
        assertEquals(200, failed.statusCode());
        JsonNode failedBody = jsonMapper.readTree(failed.body());
        assertEquals(1, failedBody.size());
        assertEquals("FAILED", failedBody.get(0).get("status").asText());
        assertEquals(500, failedBody.get(0).get("httpStatus").asInt());

        HttpResponse<String> succeeded = send("GET", "/api/v1/deliveries?status=SUCCEEDED", null);
        assertEquals(200, succeeded.statusCode());
        JsonNode succeededBody = jsonMapper.readTree(succeeded.body());
        assertEquals(1, succeededBody.size());
        assertEquals("SUCCEEDED", succeededBody.get(0).get("status").asText());

        HttpResponse<String> all = send("GET", "/api/v1/deliveries", null);
        assertEquals(200, all.statusCode());
        assertEquals(2, jsonMapper.readTree(all.body()).size());
        HttpResponse<String> pending = send("GET", "/api/v1/deliveries?status=PENDING", null);
        assertEquals(200, pending.statusCode());
        assertEquals(0, jsonMapper.readTree(pending.body()).size());
    }

    @Test
    void rejectsUnknownDeliveryStatus() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/deliveries?status=UNKNOWN", null);

        assertEquals(400, response.statusCode());
        assertEquals(0, deliveryRepository.count());
    }
    private long createEndpoint() throws Exception {
        String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/webhook";
        HttpResponse<String> response = send("POST", "/api/v1/endpoints", Map.of("url", url));
        assertEquals(201, response.statusCode());
        return jsonMapper.readTree(response.body()).get("id").asLong();
    }

    private long createEvent(String payload) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/events",
                Map.of("type", "order.created", "payload", payload));
        assertEquals(201, response.statusCode());
        return jsonMapper.readTree(response.body()).get("id").asLong();
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
