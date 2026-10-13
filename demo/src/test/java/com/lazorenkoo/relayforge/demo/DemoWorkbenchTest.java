package com.lazorenkoo.relayforge.demo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoWorkbenchTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private RelayApi relay;
    private DemoWorkbench workbench;

    @BeforeEach
    void setUp() {
        relay = mock(RelayApi.class);
        workbench = new DemoWorkbench(relay, "http://demo:8090");
        when(relay.request("GET", "/api/v1/endpoints", null, null)).thenReturn(mapper.readTree("[]"));
        when(relay.request(eq("POST"), eq("/api/v1/endpoints"), any(), isNull())).thenReturn(mapper.readTree("{\"id\":1}"));
        when(relay.request("POST", "/api/v1/endpoints/1/rotate-secret", null, null))
                .thenReturn(mapper.readTree("{\"secret\":\"test-secret\"}"));
    }

    @Test
    void verifiesExactBodyAndDoesNotExposeSecret() throws Exception {
        DemoClient client = workbench.create("Склад", "receiver", List.of("order.paid"));
        String body = "{\"id\":1,\"data\":{\"text\":\"Привет ✓\"}}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        DemoMessage accepted = workbench.receive(client.id(), body, mapper.readTree(body), timestamp, sign(timestamp, body));
        assertTrue(accepted.signatureValid());
        assertEquals(200, accepted.responseStatus());
        DemoMessage changed = workbench.receive(client.id(), body + " ", mapper.readTree(body), timestamp, sign(timestamp, body));
        assertFalse(changed.signatureValid());
        assertEquals(403, changed.responseStatus());
        assertFalse(mapper.writeValueAsString(client).contains("secret"));
        assertFalse(DemoWorkbench.validSignature("test-secret", "1", sign(timestamp, body), body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "error", "timeout"})
    void recordsSelectedReceiverBehavior(String mode) throws Exception {
        DemoClient client = workbench.create("Склад", "receiver", List.of("order.paid"));
        workbench.update(client.id(), client.name(), client.role(), mode, client.subscriptions());
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        DemoMessage message = workbench.receive(client.id(), "{}", mapper.readTree("{}"), timestamp, sign(timestamp, "{}"));
        assertTrue(message.signatureValid());
        assertEquals(mode, message.mode());
        assertEquals(mode.equals("error") ? 500 : 200, message.responseStatus());
    }

    @Test
    void limitsClientsAndRejectsInvalidRole() {
        for (int number = 0; number < 10; number++) workbench.create("Client " + number, "sender", List.of());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> workbench.create("Extra", "sender", List.of())).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> workbench.update("missing", "Name", null, "success", List.of())).getStatusCode().value());
    }

    @Test
    void removesSubscriptionsWithoutDeletingDeliveryHistory() {
        DemoClient client = workbench.create("Склад", "receiver", List.of("order.paid"));
        workbench.remove(client.id());
        verify(relay).request("PUT", "/api/v1/endpoints/1/subscriptions", Map.of("subscribedEvents", List.of()), null);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> workbench.find(client.id())).getStatusCode().value());
        verify(relay, never()).request(eq("DELETE"), anyString(), any(), any());
    }

    @Test
    void clearsOnlyOldDemoSubscriptionsAfterRestart() {
        when(relay.request("GET", "/api/v1/endpoints", null, null)).thenReturn(mapper.readTree(
                "[{\"id\":42,\"url\":\"http://demo:8090/hooks/old\"},{\"id\":43,\"url\":\"https://other.example/webhook\"}]"));
        workbench.create("Магазин", "sender", List.of());
        workbench.create("Другой магазин", "sender", List.of());
        verify(relay).request("PUT", "/api/v1/endpoints/42/subscriptions", Map.of("subscribedEvents", List.of()), null);
        verify(relay).request("PATCH", "/api/v1/endpoints/42/enable", null, null);
        verify(relay, never()).request(eq("PUT"), eq("/api/v1/endpoints/43/subscriptions"), any(), any());
        verify(relay, times(1)).request("GET", "/api/v1/endpoints", null, null);
    }

    @Test
    void sendsJsonAndKeepsIdempotencyKey() {
        DemoClient sender = workbench.create("Магазин", "sender", List.of());
        JsonNode event = mapper.readTree("{\"id\":12}");
        JsonNode data = mapper.readTree("{\"orderId\":123}");
        when(relay.request("POST", "/api/v1/events", Map.of("type", "order.paid", "payload", data.toString()), "order-123"))
                .thenReturn(event);
        assertEquals(event, workbench.send(sender.id(), "order.paid", data, "order-123"));
        verify(relay).request("POST", "/api/v1/events", Map.of("type", "order.paid", "payload", data.toString()), "order-123");
    }

    @Test
    void changingToSenderStopsNewSubscriptionsWithoutBlockingPendingRequests() {
        DemoClient receiver = workbench.create("Склад", "receiver", List.of("order.paid"));
        DemoClient sender = workbench.update(receiver.id(), receiver.name(), "sender", "success", List.of());
        assertTrue(sender.sends());
        assertFalse(sender.receives());
        verify(relay).request("PUT", "/api/v1/endpoints/1/subscriptions", Map.of("subscribedEvents", List.of()), null);
        verify(relay).request("PATCH", "/api/v1/endpoints/1/enable", null, null);
        verify(relay, never()).request("PATCH", "/api/v1/endpoints/1/disable", null, null);
    }

    private String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
