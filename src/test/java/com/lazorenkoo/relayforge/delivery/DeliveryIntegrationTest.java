package com.lazorenkoo.relayforge.delivery;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.lazorenkoo.relayforge.endpoint.EndpointRepository;
import com.lazorenkoo.relayforge.event.EventRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private DeliveryResultService resultService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

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

        HttpResponse<String> history = send("GET", "/api/v1/deliveries/" + delivery.getId() + "/attempts", null);
        assertEquals(200, history.statusCode());
        JsonNode attempts = jsonMapper.readTree(history.body());
        assertEquals(1, attempts.size());
        JsonNode attempt = attempts.get(0);
        assertEquals(delivery.getId(), attempt.get("deliveryId").asLong());
        assertEquals(1, attempt.get("attemptNumber").asInt());
        assertEquals("FAILED", attempt.get("status").asText());
        assertTrue(attempt.get("httpStatus").isNull());
        assertEquals(delivery.getLastError(), attempt.get("error").asText());
        assertEquals(delivery.getCompletedAt(), Instant.parse(attempt.get("completedAt").asText()));
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
    @Test
    void filtersDeliveriesByEventAndStatus() throws Exception {
        long endpointId = createEndpoint();
        long firstEventId = createEvent("{\"orderId\":1}");
        long secondEventId = createEvent("{\"orderId\":2}");
        Map<String, Long> firstRequest = Map.of("eventId", firstEventId, "endpointId", endpointId);
        assertEquals(200, send("POST", "/api/v1/deliveries", firstRequest).statusCode());
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries", firstRequest).statusCode());
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", secondEventId, "endpointId", endpointId)).statusCode());

        HttpResponse<String> eventHistory = send("GET", "/api/v1/deliveries?eventId=" + firstEventId, null);
        assertEquals(200, eventHistory.statusCode());
        JsonNode historyBody = jsonMapper.readTree(eventHistory.body());
        assertEquals(2, historyBody.size());
        for (JsonNode delivery : historyBody) {
            assertEquals(firstEventId, delivery.get("eventId").asLong());
        }

        HttpResponse<String> failed = send("GET",
                "/api/v1/deliveries?eventId=" + firstEventId + "&status=FAILED", null);
        assertEquals(200, failed.statusCode());
        JsonNode failedBody = jsonMapper.readTree(failed.body());
        assertEquals(1, failedBody.size());
        assertEquals(firstEventId, failedBody.get(0).get("eventId").asLong());
        assertEquals("FAILED", failedBody.get(0).get("status").asText());

        HttpResponse<String> empty = send("GET", "/api/v1/deliveries?eventId=999999", null);
        assertEquals(200, empty.statusCode());
        assertEquals(0, jsonMapper.readTree(empty.body()).size());
    }

    @Test
    void rejectsInvalidEventFilter() throws Exception {
        assertEquals(400, send("GET", "/api/v1/deliveries?eventId=0", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/deliveries?eventId=-1", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/deliveries?eventId=abc", null).statusCode());
        assertEquals(0, deliveryRepository.count());
    }
    @Test
    void retriesFailedDeliveryAndPreservesOriginalHistory() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{\"orderId\":1}");
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId)).statusCode());
        Delivery original = deliveryRepository.findAll().getFirst();
        receiverStatus.set(200);

        HttpResponse<String> response = send("POST",
                "/api/v1/deliveries/" + original.getId() + "/retry", null);

        assertEquals(200, response.statusCode());
        assertTrue(jsonMapper.readTree(response.body()).get("successful").asBoolean());
        assertEquals(2, deliveryRepository.count());
        Delivery previous = deliveryRepository.findById(original.getId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, previous.getStatus());
        assertEquals(500, previous.getHttpStatus());
        assertEquals(original.getCompletedAt(), previous.getCompletedAt());
        Delivery retried = deliveryRepository.findByStatus(DeliveryStatus.SUCCEEDED).getFirst();
        assertNotEquals(original.getId(), retried.getId());
        assertEquals(eventId, retried.getEventId());
        assertEquals(endpointId, retried.getEndpointId());
        assertEquals(200, retried.getHttpStatus());
    }

    @Test
    void rejectsRetryOfSuccessfulDelivery() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId)).statusCode());
        Delivery delivery = deliveryRepository.findAll().getFirst();
        receivedPayload.set(null);

        HttpResponse<String> response = send("POST",
                "/api/v1/deliveries/" + delivery.getId() + "/retry", null);

        assertEquals(409, response.statusCode());
        assertEquals(1, deliveryRepository.count());
        assertNull(receivedPayload.get());
    }

    @Test
    void rejectsRetryOfMissingDelivery() throws Exception {
        assertEquals(404, send("POST", "/api/v1/deliveries/999999/retry", null).statusCode());
        assertEquals(404, send("GET", "/api/v1/deliveries/999999/attempts", null).statusCode());
        assertEquals(0, deliveryRepository.count());
        assertNull(receivedPayload.get());
    }
    @ParameterizedTest
    @ValueSource(strings = {"plain text", "{", "{} {}", "{\"orderId\":1} extra"})
    void rejectsMalformedEventJsonBeforeSaving(String payload) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/events",
                Map.of("type", "order.created", "payload", payload));

        assertEquals(400, response.statusCode());
        assertEquals(0, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
        assertNull(receivedPayload.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"orderId\":123}", "[1,2,3]", " \n {\"message\":\"Привет\"} \n "})
    void acceptsValidJsonWithoutChangingPayload(String payload) throws Exception {
        long eventId = createEvent(payload);

        assertEquals(payload, eventRepository.findById(eventId).orElseThrow().getPayload());
        HttpResponse<String> response = send("GET", "/api/v1/events/" + eventId, null);
        assertEquals(200, response.statusCode());
        assertEquals(payload, jsonMapper.readTree(response.body()).get("payload").asText());
    }

    @Test
    void enforcesEventTypeLengthBeforeSaving() throws Exception {
        HttpResponse<String> tooLong = send("POST", "/api/v1/events",
                Map.of("type", "a".repeat(256), "payload", "{}"));
        assertEquals(400, tooLong.statusCode());
        assertEquals(0, eventRepository.count());

        HttpResponse<String> allowed = send("POST", "/api/v1/events",
                Map.of("type", "a".repeat(255), "payload", "{}"));
        assertEquals(201, allowed.statusCode());
        assertEquals(1, eventRepository.count());
    }
    @Test
    void workerSendsSavedDeliveryAndSkipsDisabledEndpoint() throws Exception {
        long endpointId = createEndpoint();
        assertEquals(200, send("PUT", "/api/v1/endpoints/" + endpointId + "/subscriptions",
                Map.of("subscribedEvents", java.util.List.of("order.created"))).statusCode());
        String payload = "{\"orderId\":123}";
        long eventId = createEvent(payload);
        Delivery pending = deliveryRepository.findByEventId(eventId).getFirst();
        assertEquals(DeliveryStatus.PENDING, pending.getStatus());
        DeliveryWorker worker = new DeliveryWorker(deliveryRepository, deliveryService, resultService);

        assertEquals(200, send("PATCH", "/api/v1/endpoints/" + endpointId + "/disable", null).statusCode());
        worker.sendPendingDeliveries();
        assertNull(receivedPayload.get());
        assertEquals(DeliveryStatus.PENDING,
                deliveryRepository.findById(pending.getId()).orElseThrow().getStatus());

        assertEquals(200, send("PATCH", "/api/v1/endpoints/" + endpointId + "/enable", null).statusCode());
        worker.sendPendingDeliveries();

        Delivery sent = deliveryRepository.findById(pending.getId()).orElseThrow();
        assertEquals(payload, receivedPayload.get());
        assertEquals(DeliveryStatus.SUCCEEDED, sent.getStatus());
        assertEquals(200, sent.getHttpStatus());
        assertNotNull(sent.getCompletedAt());
        assertEquals(pending.getCreatedAt(), sent.getCreatedAt());
        assertEquals(1, deliveryRepository.count());

        receivedPayload.set(null);
        worker.sendPendingDeliveries();
        assertNull(receivedPayload.get());
        assertEquals(1, deliveryRepository.count());
    }

    @Test
    void workerWaitsForRetryTimeAndUpdatesOriginalDelivery() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{\"orderId\":123}");
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId)).statusCode());
        Delivery original = deliveryRepository.findAll().getFirst();
        assertEquals(1, original.getAttemptCount());
        assertNotNull(original.getNextAttemptAt());
        original.setNextAttemptAt(Instant.now().plusSeconds(3600));
        deliveryRepository.save(original);
        DeliveryWorker worker = new DeliveryWorker(deliveryRepository, deliveryService, resultService);
        receiverStatus.set(200);
        receivedPayload.set(null);

        worker.sendPendingDeliveries();
        assertNull(receivedPayload.get());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> deliveryService.sendScheduledRetry(original.getId())).getStatusCode().value());

        original.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(original);
        worker.sendPendingDeliveries();

        Delivery retried = deliveryRepository.findById(original.getId()).orElseThrow();
        assertEquals("{\"orderId\":123}", receivedPayload.get());
        assertEquals(DeliveryStatus.SUCCEEDED, retried.getStatus());
        assertEquals(2, retried.getAttemptCount());
        assertEquals(200, retried.getHttpStatus());
        assertNull(retried.getNextAttemptAt());
        assertNull(retried.getLastError());
        assertEquals(1, deliveryRepository.count());

        HttpResponse<String> history = send("GET", "/api/v1/deliveries/" + original.getId() + "/attempts", null);
        assertEquals(200, history.statusCode());
        JsonNode attempts = jsonMapper.readTree(history.body());
        assertEquals(2, attempts.size());
        JsonNode firstAttempt = attempts.get(0);
        assertEquals(1, firstAttempt.get("attemptNumber").asInt());
        assertEquals("FAILED", firstAttempt.get("status").asText());
        assertEquals(500, firstAttempt.get("httpStatus").asInt());
        assertEquals("Receiver returned HTTP 500", firstAttempt.get("error").asText());
        assertEquals(original.getCompletedAt(), Instant.parse(firstAttempt.get("completedAt").asText()));
        JsonNode secondAttempt = attempts.get(1);
        assertEquals(2, secondAttempt.get("attemptNumber").asInt());
        assertEquals("SUCCEEDED", secondAttempt.get("status").asText());
        assertEquals(200, secondAttempt.get("httpStatus").asInt());
        assertTrue(secondAttempt.get("error").isNull());
        assertEquals(retried.getCompletedAt(), Instant.parse(secondAttempt.get("completedAt").asText()));
        assertEquals(original.getId(), firstAttempt.get("deliveryId").asLong());
        assertEquals(original.getId(), secondAttempt.get("deliveryId").asLong());
    }

    @Test
    void manualRetryCancelsScheduledRetryOfOriginalRecord() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId)).statusCode());
        Delivery original = deliveryRepository.findAll().getFirst();
        assertNotNull(original.getNextAttemptAt());
        original.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(original);
        receiverStatus.set(200);

        assertEquals(200, send("POST", "/api/v1/deliveries/" + original.getId() + "/retry", null).statusCode());

        Delivery previous = deliveryRepository.findById(original.getId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, previous.getStatus());
        assertNull(previous.getNextAttemptAt());
        assertEquals(2, deliveryRepository.count());
        receivedPayload.set(null);
        new DeliveryWorker(deliveryRepository, deliveryService, resultService).sendPendingDeliveries();
        assertNull(receivedPayload.get());
    }

    @Test
    void rollsBackDeliveryWhenAttemptCannotBeSaved() {
        Delivery delivery = deliveryRepository.save(new Delivery());
        long id = delivery.getId();
        new DeliveryClaimService(deliveryRepository).claim(delivery, DeliveryStatus.PENDING);
        delivery.setStatus(DeliveryStatus.SUCCEEDED);
        delivery.setAttemptCount(1);
        delivery.setHttpStatus(200);
        delivery.setCompletedAt(Instant.now());
        jdbcTemplate.execute("ALTER TABLE delivery_attempts ADD CONSTRAINT reject_test_attempt "
                + "CHECK (attempt_number <> 1) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> resultService.save(delivery));

            Delivery saved = deliveryRepository.findById(id).orElseThrow();
            assertEquals(DeliveryStatus.PROCESSING, saved.getStatus());
            assertEquals(0, saved.getAttemptCount());
            assertNull(saved.getHttpStatus());
            assertNull(saved.getCompletedAt());
            assertTrue(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id).isEmpty());
        } finally {
            jdbcTemplate.execute("ALTER TABLE delivery_attempts DROP CONSTRAINT reject_test_attempt");
        }
    }

    @Test
    void workerStopsAfterThreeFailedAttempts() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId)).statusCode());
        long id = deliveryRepository.findAll().getFirst().getId();
        DeliveryWorker worker = new DeliveryWorker(deliveryRepository, deliveryService, resultService);

        for (int attempt = 2; attempt <= 3; attempt++) {
            Delivery delivery = deliveryRepository.findById(id).orElseThrow();
            assertNotNull(delivery.getNextAttemptAt());
            delivery.setNextAttemptAt(Instant.now().minusSeconds(1));
            deliveryRepository.save(delivery);
            worker.sendPendingDeliveries();
            assertEquals(attempt, deliveryRepository.findById(id).orElseThrow().getAttemptCount());
        }

        Delivery exhausted = deliveryRepository.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, exhausted.getStatus());
        assertNull(exhausted.getNextAttemptAt());
        receivedPayload.set(null);
        worker.sendPendingDeliveries();
        assertNull(receivedPayload.get());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> deliveryService.sendScheduledRetry(id)).getStatusCode().value());

        java.util.List<DeliveryAttempt> attempts = attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id);
        assertEquals(3, attempts.size());
        for (int index = 0; index < attempts.size(); index++) {
            DeliveryAttempt attempt = attempts.get(index);
            assertEquals(index + 1, attempt.getAttemptNumber());
            assertEquals(DeliveryStatus.FAILED, attempt.getStatus());
            assertEquals(500, attempt.getHttpStatus());
            assertEquals("Receiver returned HTTP 500", attempt.getError());
            assertNotNull(attempt.getCompletedAt());
        }
        assertEquals(1, deliveryRepository.count());
    }

    @Test
    void preventsConcurrentSendingOfPendingDelivery() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        Delivery delivery = new Delivery();
        delivery.setEventId(eventId);
        delivery.setEndpointId(endpointId);
        long id = deliveryRepository.save(delivery).getId();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        blockReceiver(entered, release, requests);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> deliveryService.sendPending(id));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(DeliveryStatus.PROCESSING, deliveryRepository.findById(id).orElseThrow().getStatus());
                var second = executor.submit(() -> assertThrows(ResponseStatusException.class,
                        () -> deliveryService.sendPending(id)));
                assertEquals(409, second.get(3, TimeUnit.SECONDS).getStatusCode().value());
            } finally {
                release.countDown();
            }
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, requests.get());
        assertEquals(DeliveryStatus.SUCCEEDED, deliveryRepository.findById(id).orElseThrow().getStatus());
        assertEquals(1, attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id).size());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "FAILED"})
    void onlyOneServiceClaimsTheSameSnapshot(DeliveryStatus status) throws Exception {
        Delivery delivery = new Delivery();
        delivery.setStatus(status);
        long id = deliveryRepository.save(delivery).getId();
        Delivery firstSnapshot = deliveryRepository.findById(id).orElseThrow();
        Delivery secondSnapshot = deliveryRepository.findById(id).orElseThrow();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (Delivery snapshot : java.util.List.of(firstSnapshot, secondSnapshot)) {
                DeliveryClaimService service = new DeliveryClaimService(deliveryRepository);
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Claim start timed out");
                    }
                    try {
                        service.claim(snapshot, status);
                        return 200;
                    } catch (ResponseStatusException exception) {
                        return exception.getStatusCode().value();
                    }
                }));
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            java.util.Set<Integer> statuses = java.util.Set.of(
                    results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(200, 409), statuses);
        }

        assertEquals(DeliveryStatus.PROCESSING, deliveryRepository.findById(id).orElseThrow().getStatus());
        assertEquals(0, deliveryRepository.findById(id).orElseThrow().getAttemptCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preventsConcurrentRetries(boolean manual) throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        receiverStatus.set(500);
        assertEquals(200, send("POST", "/api/v1/deliveries",
                Map.of("eventId", eventId, "endpointId", endpointId)).statusCode());
        Delivery original = deliveryRepository.findAll().getFirst();
        long id = original.getId();
        original.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(original);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        blockReceiver(entered, release, requests);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> manual
                    ? deliveryService.retry(id) : deliveryService.sendScheduledRetry(id));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(DeliveryStatus.PROCESSING, deliveryRepository.findById(id).orElseThrow().getStatus());
                assertEquals(409, assertThrows(ResponseStatusException.class,
                        () -> deliveryService.retry(id)).getStatusCode().value());
                assertEquals(409, assertThrows(ResponseStatusException.class,
                        () -> deliveryService.sendScheduledRetry(id)).getStatusCode().value());
            } finally {
                release.countDown();
            }
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, requests.get());
        assertEquals(manual ? 2 : 1, deliveryRepository.count());
        assertEquals(manual ? 1 : 2, attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id).size());
        Delivery saved = deliveryRepository.findById(id).orElseThrow();
        assertEquals(manual ? DeliveryStatus.FAILED : DeliveryStatus.SUCCEEDED, saved.getStatus());
        assertNull(saved.getNextAttemptAt());
    }

    private void blockReceiver(CountDownLatch entered, CountDownLatch release, AtomicInteger requests) {
        receiver.removeContext("/webhook");
        receiver.createContext("/webhook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                entered.countDown();
                if (release.await(5, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(200, -1);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void recoversExpiredProcessingAndRejectsOldResult() throws Exception {
        long endpointId = createEndpoint();
        long eventId = createEvent("{}");
        Delivery delivery = new Delivery();
        delivery.setEndpointId(endpointId);
        delivery.setEventId(eventId);
        delivery = deliveryRepository.save(delivery);
        long id = delivery.getId();
        new DeliveryClaimService(deliveryRepository).claim(delivery, DeliveryStatus.PENDING);
        Delivery oldResult = delivery;
        Delivery fresh = new Delivery();
        fresh = deliveryRepository.save(fresh);
        new DeliveryClaimService(deliveryRepository).claim(fresh, DeliveryStatus.PENDING);
        jdbcTemplate.update("UPDATE deliveries SET processing_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), id);
        DeliveryWorker worker = new DeliveryWorker(deliveryRepository, deliveryService, resultService);

        worker.sendPendingDeliveries();

        Delivery recovered = deliveryRepository.findById(id).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, recovered.getStatus());
        assertEquals(1, recovered.getAttemptCount());
        assertNotNull(recovered.getNextAttemptAt());
        assertNull(recovered.getProcessingStartedAt());
        assertEquals(DeliveryStatus.PROCESSING, deliveryRepository.findById(fresh.getId()).orElseThrow().getStatus());
        resultService.recover(id, Instant.now());
        assertEquals(1, attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id).size());

        recovered.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(recovered);
        Delivery current = deliveryRepository.findById(id).orElseThrow();
        new DeliveryClaimService(deliveryRepository).claimRetry(current);
        oldResult.setStatus(DeliveryStatus.SUCCEEDED);
        oldResult.setAttemptCount(1);
        oldResult.setCompletedAt(Instant.now());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> resultService.save(oldResult)).getStatusCode().value());
        assertEquals(current.getProcessingVersion(), deliveryRepository.findById(id).orElseThrow().getProcessingVersion());
        assertEquals(1, attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id).size());

        jdbcTemplate.update("UPDATE deliveries SET processing_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), id);
        worker.sendPendingDeliveries();
        Delivery ready = deliveryRepository.findById(id).orElseThrow();
        ready.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(ready);
        worker.sendPendingDeliveries();
        assertEquals("{}", receivedPayload.get());
        assertEquals(DeliveryStatus.SUCCEEDED, deliveryRepository.findById(id).orElseThrow().getStatus());
        assertEquals(3, attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id).size());
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
