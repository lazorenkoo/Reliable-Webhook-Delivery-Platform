package com.lazorenkoo.relayforge.demo;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class DemoController {
    private final DemoWorkbench workbench;
    private final RelayApi relay;
    private final JsonMapper mapper;

    public DemoController(DemoWorkbench workbench, RelayApi relay, JsonMapper mapper) {
        this.workbench = workbench;
        this.relay = relay;
        this.mapper = mapper;
    }

    public record ClientRequest(String name, String role, String mode, List<String> subscriptions) { }
    public record EventRequest(String clientId, String type, JsonNode data, String idempotencyKey) { }

    @GetMapping("/demo/state")
    public Map<String, Object> state() { return workbench.snapshot(); }

    @PostMapping("/demo/clients")
    public DemoClient create(@RequestBody ClientRequest request) {
        return workbench.create(request.name(), request.role(), request.subscriptions());
    }

    @PutMapping("/demo/clients/{id}")
    public DemoClient update(@PathVariable String id, @RequestBody ClientRequest request) {
        workbench.find(id);
        return workbench.update(id, request.name(), request.role(), request.mode(), request.subscriptions());
    }

    @DeleteMapping("/demo/clients/{id}")
    public Map<String, String> remove(@PathVariable String id) {
        workbench.remove(id);
        return Map.of("status", "removed");
    }

    @PostMapping("/demo/events")
    public JsonNode send(@RequestBody EventRequest request) {
        return workbench.send(request.clientId(), request.type(), request.data(), request.idempotencyKey());
    }

    @GetMapping("/demo/deliveries/{id}/attempts")
    public JsonNode attempts(@PathVariable long id) {
        return relay.request("GET", "/api/v1/deliveries/" + id + "/attempts", null, null);
    }

    @PostMapping("/demo/deliveries/{id}/retry")
    public JsonNode retry(@PathVariable long id) {
        return relay.request("POST", "/api/v1/deliveries/" + id + "/retry", null, null);
    }

    @PostMapping("/hooks/{id}")
    public ResponseEntity<Map<String, String>> receive(@PathVariable String id, @RequestBody String body,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) throws InterruptedException {
        DemoMessage message = workbench.receive(id, body, mapper.readTree(body), timestamp, signature);
        if (message.signatureValid() && message.mode().equals("timeout")) Thread.sleep(12000);
        return ResponseEntity.status(message.responseStatus()).body(Map.of("status", message.signatureValid() ? "received" : "invalid signature"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("status", exception.getStatusCode().value(),
                "message", exception.getReason() == null ? "Request failed" : exception.getReason()));
    }
}
