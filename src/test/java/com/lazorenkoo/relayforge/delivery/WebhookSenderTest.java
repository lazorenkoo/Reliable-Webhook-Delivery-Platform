package com.lazorenkoo.relayforge.delivery;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebhookSenderTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void sendsJsonAndReturnsReceiverStatus(int receiverStatus) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/webhook", exchange -> {
            try (exchange) {
                receivedMethod.set(exchange.getRequestMethod());
                receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(receiverStatus, -1);
            }
        });
        server.start();

        try (HttpClient client = HttpClient.newHttpClient()) {
            WebhookSender sender = new WebhookSender(client);
            String payload = "{\"message\":\"Привет\"}";
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";

            assertEquals(receiverStatus, sender.send(url, payload));
            assertEquals("POST", receivedMethod.get());
            assertEquals("application/json", receivedContentType.get());
            assertEquals(payload, receivedBody.get());
        } finally {
            server.stop(0);
        }
    }
}
