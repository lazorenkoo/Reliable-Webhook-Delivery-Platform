package com.lazorenkoo.relayforge.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class DemoWorkbench {
    private final RelayApi relay;
    private final String receiverUrl;
    private final Map<String, DemoClient> clients = new LinkedHashMap<>();
    private final Map<Long, String> senders = new LinkedHashMap<>();
    private final List<DemoMessage> messages = new ArrayList<>();
    private long messageId;
    private boolean initialized;

    public DemoWorkbench(RelayApi relay, @Value("${demo.receiver-url}") String receiverUrl) {
        this.relay = relay;
        this.receiverUrl = receiverUrl;
    }

    public synchronized DemoClient create(String name, String role, List<String> subscriptions) {
        if (clients.values().stream().filter(DemoClient::active).count() >= 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum 10 clients");
        }
        String id = UUID.randomUUID().toString();
        return update(id, name, role, "success", subscriptions);
    }

    public synchronized DemoClient update(String id, String name, String role, String mode, List<String> subscriptions) {
        if (name == null || name.isBlank() || name.length() > 60 || role == null || mode == null ||
                !List.of("sender", "receiver", "both").contains(role) ||
                !List.of("success", "error", "timeout").contains(mode) || subscriptions == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid client name, role or subscriptions");
        }
        DemoClient old = clients.get(id);
        if (!initialized) {
            for (JsonNode endpoint : relay.request("GET", "/api/v1/endpoints", null, null)) {
                if (endpoint.path("url").asText().startsWith(receiverUrl + "/hooks/")) {
                    String path = "/api/v1/endpoints/" + endpoint.path("id").asLong();
                    relay.request("PUT", path + "/subscriptions", Map.of("subscribedEvents", List.of()), null);
                    relay.request("PATCH", path + "/enable", null, null);
                }
            }
            initialized = true;
        }
        Long endpointId = old == null ? null : old.endpointId();
        String secret = old == null ? null : old.secret();
        boolean receives = !role.equals("sender");
        if (receives && endpointId == null) {
            endpointId = relay.request("POST", "/api/v1/endpoints",
                    Map.of("url", receiverUrl + "/hooks/" + id, "subscribedEvents", subscriptions), null).path("id").asLong();
            secret = relay.request("POST", "/api/v1/endpoints/" + endpointId + "/rotate-secret", null, null).path("secret").asText();
        } else if (endpointId != null) {
            relay.request("PUT", "/api/v1/endpoints/" + endpointId + "/subscriptions",
                    Map.of("subscribedEvents", receives ? subscriptions : List.of()), null);
            relay.request("PATCH", "/api/v1/endpoints/" + endpointId + "/enable", null, null);
        }
        DemoClient client = new DemoClient(id, name.strip(), role, mode, List.copyOf(subscriptions), endpointId, secret, true);
        clients.put(id, client);
        return client;
    }

    public synchronized void remove(String id) {
        DemoClient client = find(id);
        if (client.endpointId() != null) relay.request("PUT", "/api/v1/endpoints/" + client.endpointId() + "/subscriptions",
                Map.of("subscribedEvents", List.of()), null);
        clients.put(id, new DemoClient(id, client.name(), client.role(), client.mode(), client.subscriptions(),
                client.endpointId(), client.secret(), false));
    }

    public synchronized DemoClient find(String id) {
        DemoClient client = clients.get(id);
        if (client == null || !client.active()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found");
        return client;
    }

    public JsonNode send(String clientId, String type, JsonNode data, String key) {
        DemoClient client = find(clientId);
        if (!client.sends()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client cannot send events");
        if (type == null || data == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event type and JSON are required");
        JsonNode event = relay.request("POST", "/api/v1/events", Map.of("type", type, "payload", data.toString()), key);
        synchronized (this) { senders.putIfAbsent(event.path("id").asLong(), client.name()); }
        return event;
    }

    public Map<String, Object> snapshot() {
        JsonNode events = relay.request("GET", "/api/v1/events", null, null);
        JsonNode deliveries = relay.request("GET", "/api/v1/deliveries", null, null);
        synchronized (this) {
            Map<Long, String> endpointNames = new LinkedHashMap<>();
            clients.values().forEach(client -> {
                if (client.endpointId() != null) endpointNames.put(client.endpointId(), client.name());
            });
            return Map.of("clients", clients.values().stream().filter(DemoClient::active).toList(),
                    "events", events, "deliveries", deliveries, "messages", List.copyOf(messages),
                    "senders", Map.copyOf(senders), "endpointNames", endpointNames);
        }
    }

    public synchronized DemoMessage receive(String clientId, String body, JsonNode parsedBody, String timestamp, String signature) {
        DemoClient client = find(clientId);
        if (!client.receives()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiver is disabled");
        boolean valid = validSignature(client.secret(), timestamp, signature, body);
        int status = !valid ? 403 : client.mode().equals("error") ? 500 : 200;
        DemoMessage message = new DemoMessage(++messageId, clientId, client.name(), Instant.now(), parsedBody,
                valid, client.mode(), status);
        messages.add(message);
        if (messages.size() > 200) messages.removeFirst();
        return message;
    }

    static boolean validSignature(String secret, String timestamp, String signature, String body) {
        if (secret == null || timestamp == null || signature == null) return false;
        try {
            long sentAt = Long.parseLong(timestamp);
            if (sentAt < Instant.now().getEpochSecond() - 300 || sentAt > Instant.now().getEpochSecond() + 30) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { return false; }
    }
}
