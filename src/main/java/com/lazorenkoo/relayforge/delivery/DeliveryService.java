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
    private final DeliveryAttemptRepository attemptRepository;
    private final DeliveryResultService resultService;
    private final DeliveryClaimService claimService;

    public DeliveryService(EventService eventService, EndpointService endpointService,
                           WebhookSender webhookSender, DeliveryRepository deliveryRepository,
                           DeliveryAttemptRepository attemptRepository, DeliveryResultService resultService,
                           DeliveryClaimService claimService) {
        this.eventService = eventService;
        this.endpointService = endpointService;
        this.webhookSender = webhookSender;
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.resultService = resultService;
        this.claimService = claimService;
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
        claimService.claim(delivery, DeliveryStatus.FAILED);
        delivery.setNextAttemptAt(null);
        try {
            return send(delivery.getEventId(), delivery.getEndpointId());
        } finally {
            resultService.finishManualRetry(delivery);
        }
    }

    public List<DeliveryAttempt> findAttempts(Long id) {
        findById(id);
        return attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(id);
    }

    public int sendScheduledRetry(Long id) {
        Delivery delivery = findById(id);
        if (delivery.getStatus() != DeliveryStatus.FAILED || delivery.getNextAttemptAt() == null
                || delivery.getNextAttemptAt().isAfter(Instant.now()) || delivery.getAttemptCount() >= 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is not ready for retry");
        }
        Event event = eventService.findById(delivery.getEventId());
        WebhookEndpoint endpoint = endpointService.findById(delivery.getEndpointId());
        if (!endpoint.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint is disabled");
        }
        claimService.claimRetry(delivery);
        return sendDelivery(delivery, event, endpoint);
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

        claimService.claim(delivery, DeliveryStatus.PENDING);
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

        claimService.claim(delivery, DeliveryStatus.PENDING);
        return sendDelivery(delivery, event, endpoint);
    }

    private int sendDelivery(Delivery delivery, Event event, WebhookEndpoint endpoint) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setNextAttemptAt(null);
        delivery.setLastError(null);
        delivery.setHttpStatus(null);
        delivery.setCompletedAt(null);
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
            DeliveryRetryPolicy.scheduleRetry(delivery);
            saveResult(delivery);
            return httpStatus;
        } catch (IOException exception) {
            delivery.setLastError(exception.toString());
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setCompletedAt(Instant.now());
            DeliveryRetryPolicy.scheduleRetry(delivery);
            saveResult(delivery);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Webhook delivery failed", exception);
        } catch (InterruptedException exception) {
            delivery.setLastError("Webhook delivery interrupted");
            try {
                delivery.setStatus(DeliveryStatus.FAILED);
                delivery.setCompletedAt(Instant.now());
                DeliveryRetryPolicy.scheduleRetry(delivery);
                saveResult(delivery);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook delivery interrupted", exception);
        }
    }

    private void saveResult(Delivery delivery) {
        resultService.save(delivery);
    }
}
