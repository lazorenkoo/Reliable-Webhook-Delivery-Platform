package com.lazorenkoo.relayforge.delivery;

import com.lazorenkoo.relayforge.event.Event;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WebhookPayload {

    private final JsonMapper jsonMapper;

    public WebhookPayload(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String create(Event event) {
        var body = jsonMapper.createObjectNode();
        body.put("id", event.getId());
        body.put("type", event.getType());
        body.put("createdAt", event.getCreatedAt().toString());
        body.set("data", jsonMapper.readTree(event.getPayload()));
        return jsonMapper.writeValueAsString(body);
    }
}
