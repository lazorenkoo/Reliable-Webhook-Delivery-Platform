package com.lazorenkoo.relayforge.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
public class WebhookSender {

    private final HttpClient httpClient;

    public WebhookSender(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public int send(String url, String payload, String secret, Long eventId) throws IOException, InterruptedException {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = WebhookSignature.sign(secret, timestamp, payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Webhook-Timestamp", timestamp)
                .header("X-Webhook-Id", eventId.toString())
                .header("X-Webhook-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
