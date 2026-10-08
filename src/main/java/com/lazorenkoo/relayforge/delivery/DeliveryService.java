package com.lazorenkoo.relayforge.delivery;

import java.io.IOException;
import java.util.List;

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
    private final DeliveryRepository deliveryRepository;

    public DeliveryService(EventService eventService, EndpointService endpointService,
                           WebhookSender webhookSender, DeliveryRepository deliveryRepository) {
        this.eventService = eventService;
        this.endpointService = endpointService;
        this.webhookSender = webhookSender;
        this.deliveryRepository = deliveryRepository;
    }

    public List<Delivery> findAll() {
        return deliveryRepository.findAll();
    }

    public int send(Long eventId, Long endpointId) {
        if (eventId == null || eventId <= 0 || endpointId == null || endpointId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event and endpoint IDs must be positive");
        }

        Event event = eventService.findById(eventId);
        WebhookEndpoint endpoint = endpointService.findById(endpointId);
        if (!endpoint.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint is disabled");
        }

        Delivery delivery = new Delivery();
        delivery.setEventId(eventId);
        delivery.setEndpointId(endpointId);
        delivery = deliveryRepository.save(delivery);

        try {
            int httpStatus = webhookSender.send(endpoint.getUrl(), event.getPayload());
            delivery.setHttpStatus(httpStatus);
            if (httpStatus >= 200 && httpStatus < 300) {
                delivery.setStatus(DeliveryStatus.SUCCEEDED);
            } else {
                delivery.setStatus(DeliveryStatus.FAILED);
            }
            deliveryRepository.save(delivery);
            return httpStatus;
        } catch (IOException exception) {
            delivery.setStatus(DeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Webhook delivery failed", exception);
        } catch (InterruptedException exception) {
            try {
                delivery.setStatus(DeliveryStatus.FAILED);
                deliveryRepository.save(delivery);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook delivery interrupted", exception);
        }
    }
}
