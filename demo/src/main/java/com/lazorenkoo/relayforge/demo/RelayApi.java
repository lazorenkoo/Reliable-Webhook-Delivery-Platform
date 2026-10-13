package com.lazorenkoo.relayforge.demo;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RelayApi {
    private final RestClient client;
    private final JsonMapper mapper;

    public RelayApi(@Value("${demo.relay-url}") String url, @Value("${demo.api-key}") String key,
                    JsonMapper mapper) {
        if (key.isBlank()) throw new IllegalArgumentException("RELAYFORGE_API_KEY must be configured");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-API-Key", key).build();
        this.mapper = mapper;
    }

    public JsonNode request(String method, String path, Object body, String idempotencyKey) {
        var request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) request.header("Idempotency-Key", idempotencyKey);
        if (body != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        try {
            String response = request.retrieve().body(String.class);
            return response == null || response.isBlank() ? mapper.nullNode() : mapper.readTree(response);
        } catch (RestClientResponseException exception) {
            String message = "RelayForge request failed";
            try {
                message = mapper.readTree(exception.getResponseBodyAsString()).path("message").asText(message);
            } catch (RuntimeException ignored) { }
            throw new ResponseStatusException(exception.getStatusCode(), message);
        } catch (org.springframework.web.client.RestClientException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "RelayForge is unavailable");
        }
    }
}
