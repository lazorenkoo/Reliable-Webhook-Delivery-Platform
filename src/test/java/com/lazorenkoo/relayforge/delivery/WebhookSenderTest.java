package com.lazorenkoo.relayforge.delivery;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import com.lazorenkoo.relayforge.endpoint.WebhookDnsResolver;
import com.lazorenkoo.relayforge.endpoint.WebhookUrlPolicy;
import com.lazorenkoo.relayforge.endpoint.BlockedWebhookUrlException;
import com.lazorenkoo.relayforge.config.HttpClientConfig;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookSenderTest {

    @Test
    void refusesSendingWhenDnsChangesToPrivateAddress() throws Exception {
        WebhookDnsResolver resolver = mock(WebhookDnsResolver.class);
        when(resolver.resolve("example.com")).thenReturn(
                new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        WebhookUrlPolicy policy = new WebhookUrlPolicy(false, "example.com", resolver);
        policy.validate("https://example.com/webhook");
        HttpClient client = mock(HttpClient.class);
        WebhookSender sender = new WebhookSender(client, policy);

        IOException exception = assertThrows(IOException.class,
                () -> sender.send("https://example.com/webhook", "{}", "secret", 1L));

        assertInstanceOf(BlockedWebhookUrlException.class, exception.getCause());
        verify(resolver, times(2)).resolve("example.com");
        verifyNoInteractions(client);
    }

    @Test
    void doesNotFollowReceiverRedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirectedRequests = new AtomicInteger();
        server.createContext("/webhook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", "/internal");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/internal", exchange -> {
            try (exchange) {
                redirectedRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try (HttpClient client = new HttpClientConfig().httpClient("", 3128)) {
            WebhookSender sender = new WebhookSender(client, new WebhookUrlPolicy(true, "", new WebhookDnsResolver()));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";

            assertEquals(302, sender.send(url, "{}", "secret", 1L));
            assertEquals(0, redirectedRequests.get());
        } finally {
            server.stop(0);
        }
    }

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
            WebhookSender sender = new WebhookSender(client,
                    new com.lazorenkoo.relayforge.endpoint.WebhookUrlPolicy(true, "",
                            new com.lazorenkoo.relayforge.endpoint.WebhookDnsResolver()));
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
