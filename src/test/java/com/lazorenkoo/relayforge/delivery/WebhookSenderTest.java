package com.lazorenkoo.relayforge.delivery;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSenderTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void sendsJsonAndReturnsReceiverStatus(int receiverStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        AtomicReference<String> receivedTimestamp = new AtomicReference<>();
        AtomicReference<String> receivedId = new AtomicReference<>();
        server.createContext("/webhook", exchange -> {
            try (exchange) {
                receivedMethod.set(exchange.getRequestMethod());
                receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
                receivedTimestamp.set(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
                receivedId.set(exchange.getRequestHeaders().getFirst("X-Webhook-Id"));
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(receiverStatus, -1);
            }
        });
        server.start();

        try (HttpClient client = HttpClient.newHttpClient()) {
            WebhookSender sender = new WebhookSender(client);
            String payload = "{\"message\":\"Привет\"}";
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";

            long before = Instant.now().getEpochSecond();
            assertEquals(receiverStatus, sender.send(url, payload, "test-secret", 1L));
            assertEquals("POST", receivedMethod.get());
            assertEquals("application/json", receivedContentType.get());
            assertEquals(payload, receivedBody.get());
            assertEquals("1", receivedId.get());
            long timestamp = Long.parseLong(receivedTimestamp.get());
            assertTrue(timestamp >= before && timestamp <= Instant.now().getEpochSecond());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed = (receivedTimestamp.get() + "." + receivedBody.get()).getBytes(StandardCharsets.UTF_8);
            assertEquals("sha256=" + HexFormat.of().formatHex(mac.doFinal(signed)), receivedSignature.get());
            receivedBody.set(null);
            assertThrows(IllegalArgumentException.class, () -> sender.send(url, payload, null, 1L));
            assertNull(receivedBody.get());
        } finally {
            server.stop(0);
        }
    }
}
