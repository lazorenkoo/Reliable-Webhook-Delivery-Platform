package com.lazorenkoo.relayforge.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.lazorenkoo.relayforge.endpoint.WebhookUrlPolicy;

@Service
public class WebhookSender {

    private final HttpClient httpClient;
    private final WebhookUrlPolicy urlPolicy;

    public WebhookSender(HttpClient httpClient, WebhookUrlPolicy urlPolicy) {
        this.httpClient = httpClient;
        this.urlPolicy = urlPolicy;
    }

    public int send(String url, String payload, String secret, Long eventId) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = urlPolicy.validate(url);
        } catch (ResponseStatusException exception) {
            throw new IOException("Webhook URL is no longer allowed", exception);
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = WebhookSignature.sign(secret, timestamp, payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
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
