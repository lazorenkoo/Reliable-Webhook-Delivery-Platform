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
        "spring.jpa.hibernate.ddl-auto=create-drop"
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
    private long createEndpoint() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/endpoints", Map.of(
                "url", "https://example.com/webhook", "subscribedEvents", List.of("order.created")));
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
