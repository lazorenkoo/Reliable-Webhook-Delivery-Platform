package com.lazorenkoo.relayforge.delivery;

import java.io.IOException;

import com.lazorenkoo.relayforge.endpoint.EndpointService;
import com.lazorenkoo.relayforge.endpoint.WebhookEndpoint;
import com.lazorenkoo.relayforge.event.Event;
import com.lazorenkoo.relayforge.event.EventService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryService {

    private final EventService eventService;
    private final EndpointService endpointService;
    private final WebhookSender webhookSender;

    public DeliveryService(EventService eventService, EndpointService endpointService, WebhookSender webhookSender) {
        this.eventService = eventService;
        this.endpointService = endpointService;
        this.webhookSender = webhookSender;
    }

    public int send(Long eventId, Long endpointId) throws IOException, InterruptedException {
        if (eventId == null || eventId <= 0 || endpointId == null || endpointId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event and endpoint IDs must be positive");
        }

        Event event = eventService.findById(eventId);
        WebhookEndpoint endpoint = endpointService.findById(endpointId);
        if (!endpoint.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint is disabled");
        }

        return webhookSender.send(endpoint.getUrl(), event.getPayload());
    }
}
