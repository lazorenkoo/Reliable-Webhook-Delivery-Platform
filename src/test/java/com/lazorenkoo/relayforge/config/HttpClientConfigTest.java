package com.lazorenkoo.relayforge.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HttpClientConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void routesRequestsThroughConfiguredProxy(String scheme) throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var executor = Executors.newSingleThreadExecutor();
             HttpClient client = new HttpClientConfig().httpClient("localhost", proxy.getLocalPort())) {
            proxy.setSoTimeout(5000);
            var received = executor.submit(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = reader.readLine();
                    while (!reader.readLine().isEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    return requestLine;
                }
            });
            HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://receiver.invalid/webhook"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            if (scheme.equals("https")) {
                assertThrows(IOException.class, () -> client.send(request, HttpResponse.BodyHandlers.discarding()));
                assertEquals("CONNECT receiver.invalid:443 HTTP/1.1", received.get(5, TimeUnit.SECONDS));
            } else {
                assertEquals(403, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals("POST http://receiver.invalid/webhook HTTP/1.1", received.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void rejectsInvalidProxyPortAndSupportsDirectClient() {
        HttpClientConfig config = new HttpClientConfig();
        assertThrows(IllegalArgumentException.class, () -> config.httpClient("localhost", 0));
        assertThrows(IllegalArgumentException.class, () -> config.httpClient("localhost", 65536));
        try (HttpClient client = config.httpClient("", 3128)) {
            assertTrue(client.proxy().isEmpty());
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        }
    }
}
