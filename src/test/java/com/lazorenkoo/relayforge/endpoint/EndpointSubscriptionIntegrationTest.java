package com.lazorenkoo.relayforge.endpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.lazorenkoo.relayforge.delivery.Delivery;
import com.lazorenkoo.relayforge.delivery.DeliveryRepository;
import com.lazorenkoo.relayforge.delivery.DeliveryStatus;
import com.lazorenkoo.relayforge.event.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        "spring.jpa.hibernate.ddl-auto=validate"
})
class EndpointSubscriptionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private HttpClient client;

    @BeforeEach
    void setUp() {
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        endpointRepository.deleteAll();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void savesSubscriptionsAndReturnsThemThroughApi() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/endpoints", Map.of(
                "url", "https://example.com/webhook",
                "subscribedEvents", List.of("order.created", "order.paid", "order.created")));
        assertEquals(201, created.statusCode());
        JsonNode body = jsonMapper.readTree(created.body());
        long id = body.get("id").asLong();
        assertEquals(2, body.get("subscribedEvents").size());
        assertEquals(Set.of("order.created", "order.paid"),
                endpointRepository.findById(id).orElseThrow().getSubscribedEvents());

        HttpResponse<String> details = send("GET", "/api/v1/endpoints/" + id, null);
        assertEquals(200, details.statusCode());
        assertEquals(2, jsonMapper.readTree(details.body()).get("subscribedEvents").size());
        HttpResponse<String> list = send("GET", "/api/v1/endpoints", null);
        assertEquals(200, list.statusCode());
        assertEquals(2, jsonMapper.readTree(list.body()).get(0).get("subscribedEvents").size());
    }

    @Test
    void replacesAndClearsSubscriptionsWithoutChangingEndpoint() throws Exception {
        long id = createEndpoint();
        HttpResponse<String> replaced = send("PUT", "/api/v1/endpoints/" + id + "/subscriptions",
                Map.of("subscribedEvents", List.of("user.created")));
        assertEquals(200, replaced.statusCode());
        WebhookEndpoint saved = endpointRepository.findById(id).orElseThrow();
        assertEquals(Set.of("user.created"), saved.getSubscribedEvents());
        assertEquals("https://example.com/webhook", saved.getUrl());
        assertTrue(saved.isEnabled());

        HttpResponse<String> cleared = send("PUT", "/api/v1/endpoints/" + id + "/subscriptions",
                Map.of("subscribedEvents", List.of()));
        assertEquals(200, cleared.statusCode());
        assertTrue(endpointRepository.findById(id).orElseThrow().getSubscribedEvents().isEmpty());
    }

    @Test
    void createsEndpointWithoutSubscriptions() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/endpoints",
                Map.of("url", "https://example.com/webhook"));
        assertEquals(201, response.statusCode());
        JsonNode body = jsonMapper.readTree(response.body());
        assertEquals(0, body.get("subscribedEvents").size());
        assertTrue(endpointRepository.findById(body.get("id").asLong()).orElseThrow()
                .getSubscribedEvents().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsEmptyEventTypesBeforeCreatingEndpoint(String eventType) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/endpoints", Map.of(
                "url", "https://example.com/webhook", "subscribedEvents", List.of(eventType)));
        assertEquals(400, response.statusCode());
        assertEquals(0, endpointRepository.count());
    }

    @Test
    void rejectsNullListAndNullEventType() throws Exception {
        JsonNode nullList = jsonMapper.readTree(
                "{\"url\":\"https://example.com/webhook\",\"subscribedEvents\":null}");
        JsonNode nullEvent = jsonMapper.readTree(
                "{\"url\":\"https://example.com/webhook\",\"subscribedEvents\":[null]}");
        assertEquals(400, send("POST", "/api/v1/endpoints", nullList).statusCode());
        assertEquals(400, send("POST", "/api/v1/endpoints", nullEvent).statusCode());
        assertEquals(0, endpointRepository.count());
    }

    @Test
    void rejectsInvalidUpdateAndPreservesSubscriptions() throws Exception {
        long id = createEndpoint();
        String path = "/api/v1/endpoints/" + id + "/subscriptions";
        assertEquals(400, send("PUT", path, Map.of("subscribedEvents", List.of("a".repeat(256)))).statusCode());
        assertEquals(400, send("PUT", path, Map.of()).statusCode());
        assertEquals(Set.of("order.created"), endpointRepository.findById(id).orElseThrow().getSubscribedEvents());
    }

    @Test
    void returnsNotFoundForMissingEndpoint() throws Exception {
        HttpResponse<String> response = send("PUT", "/api/v1/endpoints/999999/subscriptions",
                Map.of("subscribedEvents", List.of("order.created")));
        assertEquals(404, response.statusCode());
        assertEquals(0, endpointRepository.count());
    }

    @Test
    void findsOnlyEnabledEndpointsSubscribedToEventType() throws Exception {
        long matchingId = createEndpoint();
        HttpResponse<String> other = send("POST", "/api/v1/endpoints", Map.of(
                "url", "https://example.com/other", "subscribedEvents", List.of("user.created")));
        assertEquals(201, other.statusCode());
        long disabledId = createEndpoint();
        assertEquals(200, send("PATCH", "/api/v1/endpoints/" + disabledId + "/disable", null).statusCode());
        assertEquals(201, send("POST", "/api/v1/endpoints",
                Map.of("url", "https://example.com/no-subscriptions")).statusCode());

        List<WebhookEndpoint> endpoints = endpointRepository.findSubscribedEndpoints("order.created");

        assertEquals(1, endpoints.size());
        assertEquals(matchingId, endpoints.getFirst().getId());
        assertTrue(endpointRepository.findSubscribedEndpoints("order.paid").isEmpty());
    }
    @Test
    void createsPendingDeliveriesForMatchingEnabledEndpoints() throws Exception {
        long firstId = createEndpoint();
        long secondId = createEndpoint();
        long disabledId = createEndpoint();
        assertEquals(200, send("PATCH", "/api/v1/endpoints/" + disabledId + "/disable", null).statusCode());
        assertEquals(201, send("POST", "/api/v1/endpoints", Map.of(
                "url", "https://example.com/other", "subscribedEvents", List.of("user.created"))).statusCode());
        assertEquals(201, send("POST", "/api/v1/endpoints",
                Map.of("url", "https://example.com/no-subscriptions")).statusCode());

        HttpResponse<String> response = send("POST", "/api/v1/events",
                Map.of("type", "order.created", "payload", "{}"));

        assertEquals(201, response.statusCode());
        long eventId = jsonMapper.readTree(response.body()).get("id").asLong();
        List<Delivery> deliveries = deliveryRepository.findByEventId(eventId);
        assertEquals(2, deliveries.size());
        Set<Long> endpointIds = new java.util.HashSet<>();
        for (Delivery delivery : deliveries) {
            endpointIds.add(delivery.getEndpointId());
            assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
            assertNotNull(delivery.getCreatedAt());
            assertNull(delivery.getCompletedAt());
            assertNull(delivery.getHttpStatus());
        }
        assertEquals(Set.of(firstId, secondId), endpointIds);
    }

    @Test
    void savesEventWithoutDeliveriesWhenThereAreNoMatchingSubscriptions() throws Exception {
        createEndpoint();

        HttpResponse<String> response = send("POST", "/api/v1/events",
                Map.of("type", "user.created", "payload", "{}"));

        assertEquals(201, response.statusCode());
        assertEquals(1, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
    }

    @Test
    void rejectsInvalidEventWithoutCreatingDeliveriesForSubscribers() throws Exception {
        createEndpoint();

        HttpResponse<String> response = send("POST", "/api/v1/events",
                Map.of("type", "order.created", "payload", "broken json"));

        assertEquals(400, response.statusCode());
        assertEquals(0, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
    }
    @Test
    void repeatedKeyReturnsSameEventWithoutExtraDeliveries() throws Exception {
        createEndpoint();
        Map<String, String> body = Map.of("type", "order.created", "payload", "{}");

        HttpResponse<String> first = sendEvent("order-123", body);
        HttpResponse<String> repeated = sendEvent("order-123", body);

        assertEquals(201, first.statusCode());
        assertEquals(201, repeated.statusCode());
        assertEquals(jsonMapper.readTree(first.body()).get("id").asLong(),
                jsonMapper.readTree(repeated.body()).get("id").asLong());
        assertEquals(409, sendEvent("order-123",
                Map.of("type", "order.paid", "payload", "{}")).statusCode());
        assertEquals(409, sendEvent("order-123",
                Map.of("type", "order.created", "payload", "{\"id\":2}")).statusCode());
        assertEquals(1, eventRepository.count());
        assertEquals(1, deliveryRepository.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void rejectsInvalidIdempotencyKeyBeforeSaving(int length) throws Exception {
        HttpResponse<String> response = sendEvent("a".repeat(length),
                Map.of("type", "order.created", "payload", "{}"));

        assertEquals(400, response.statusCode());
        assertEquals(0, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
    }

    @Test
    void concurrentRequestsCreateOneEventAndDelivery() throws Exception {
        createEndpoint();
        String key = "a".repeat(255);
        Map<String, String> body = Map.of("type", "order.created", "payload", "{}");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<HttpResponse<String>>> responses = new java.util.ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Request start timed out");
                    }
                    return sendEvent(key, body);
                }));
            }
            try {
                assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            HttpResponse<String> first = responses.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS);
            HttpResponse<String> second = responses.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(201, first.statusCode());
            assertEquals(201, second.statusCode());
            assertEquals(jsonMapper.readTree(first.body()).get("id").asLong(),
                    jsonMapper.readTree(second.body()).get("id").asLong());
        }
        assertEquals(1, eventRepository.count());
        assertEquals(1, deliveryRepository.count());
    }

    private HttpResponse<String> sendEvent(String key, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                .timeout(Duration.ofSeconds(20))
                .header("X-API-Key", "test-api-key")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rotatesSecretWithoutExposingItInEndpointResponses() throws Exception {
        long id = createEndpoint();
        String previous = endpointRepository.findById(id).orElseThrow().getSigningSecret();
        assertTrue(previous.matches("[0-9a-f]{64}"));

        HttpResponse<String> rotated = send("POST", "/api/v1/endpoints/" + id + "/rotate-secret", null);

        assertEquals(200, rotated.statusCode());
        String secret = jsonMapper.readTree(rotated.body()).get("secret").asText();
        assertTrue(secret.matches("[0-9a-f]{64}"));
        assertNotEquals(previous, secret);
        assertEquals(secret, endpointRepository.findById(id).orElseThrow().getSigningSecret());
        HttpResponse<String> details = send("GET", "/api/v1/endpoints/" + id, null);
        HttpResponse<String> list = send("GET", "/api/v1/endpoints", null);
        assertNull(jsonMapper.readTree(details.body()).get("signingSecret"));
        assertNull(jsonMapper.readTree(list.body()).get(0).get("signingSecret"));
        assertFalse(details.body().contains(secret));
        assertFalse(list.body().contains(secret));
        assertEquals(404, send("POST", "/api/v1/endpoints/999999/rotate-secret", null).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong-key"})
    void rejectsEventCreationWithoutValidApiKey(String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"order.created\",\"payload\":\"{}\"}"));
        if (!key.isEmpty()) {
            request.header("X-API-Key", key);
        }

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow().split(";")[0]);
        assertEquals("Valid API key is required", jsonMapper.readTree(response.body()).get("message").asText());
        assertEquals(401, jsonMapper.readTree(response.body()).get("status").asInt());
        assertEquals("/api/v1/events", jsonMapper.readTree(response.body()).get("path").asText());
        assertFalse(response.body().contains("test-api-key"));
        assertFalse(response.body().contains("wrong-key"));
        assertEquals(0, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
    }

    @Test
    void healthIsAvailableWithoutApiKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoints", "endpoints;param=1"})
    void cannotRotateSecretWithoutApiKey(String endpointPath) throws Exception {
        long id = createEndpoint();
        String original = endpointRepository.findById(id).orElseThrow().getSigningSecret();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/" + endpointPath + "/" + id + "/rotate-secret"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertFalse(response.body().contains(original));
        assertEquals(original, endpointRepository.findById(id).orElseThrow().getSigningSecret());
    }

    private long createEndpoint() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/endpoints", Map.of(
                "url", "https://example.com/webhook", "subscribedEvents", List.of("order.created")));
        assertEquals(201, response.statusCode());
        assertNull(jsonMapper.readTree(response.body()).get("signingSecret"));
        return jsonMapper.readTree(response.body()).get("id").asLong();
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-API-Key", "test-api-key");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
