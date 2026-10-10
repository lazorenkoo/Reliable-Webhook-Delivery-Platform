package com.lazorenkoo.relayforge.delivery;

import java.io.IOException;
import java.time.Instant;
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

    public List<Delivery> findAll(DeliveryStatus status, Long eventId) {
        if (eventId != null) {
            if (eventId <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event ID must be positive");
            }
            if (status != null) {
                return deliveryRepository.findByEventIdAndStatus(eventId, status);
            }
            return deliveryRepository.findByEventId(eventId);
        }
        if (status != null) {
            return deliveryRepository.findByStatus(status);
        }
        return deliveryRepository.findAll();
    }

    public Delivery findById(Long id) {
        return deliveryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
    }

    public int retry(Long id) {
        Delivery delivery = findById(id);
        if (delivery.getStatus() != DeliveryStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed deliveries can be retried");
        }
        return send(delivery.getEventId(), delivery.getEndpointId());
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

        return sendDelivery(delivery, event, endpoint);
    }

    public int sendPending(Long id) {
        Delivery delivery = findById(id);
        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is not pending");
        }

        Event event = eventService.findById(delivery.getEventId());
        WebhookEndpoint endpoint = endpointService.findById(delivery.getEndpointId());
        if (!endpoint.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint is disabled");
        }

        return sendDelivery(delivery, event, endpoint);
    }

    private int sendDelivery(Delivery delivery, Event event, WebhookEndpoint endpoint) {
        try {
            int httpStatus = webhookSender.send(endpoint.getUrl(), event.getPayload());
            delivery.setHttpStatus(httpStatus);
            if (httpStatus >= 200 && httpStatus < 300) {
                delivery.setStatus(DeliveryStatus.SUCCEEDED);
            } else {
                delivery.setStatus(DeliveryStatus.FAILED);
                delivery.setLastError("Receiver returned HTTP " + httpStatus);
            }
            delivery.setCompletedAt(Instant.now());
            deliveryRepository.save(delivery);
            return httpStatus;
        } catch (IOException exception) {
            delivery.setLastError(exception.toString());
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setCompletedAt(Instant.now());
            deliveryRepository.save(delivery);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Webhook delivery failed", exception);
        } catch (InterruptedException exception) {
            delivery.setLastError("Webhook delivery interrupted");
            try {
                delivery.setStatus(DeliveryStatus.FAILED);
                delivery.setCompletedAt(Instant.now());
                deliveryRepository.save(delivery);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook delivery interrupted", exception);
        }
    }
}
