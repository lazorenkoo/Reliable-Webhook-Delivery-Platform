package com.lazorenkoo.relayforge.event;

import java.util.List;

import com.lazorenkoo.relayforge.delivery.Delivery;
import com.lazorenkoo.relayforge.delivery.DeliveryRepository;
import com.lazorenkoo.relayforge.endpoint.EndpointRepository;
import com.lazorenkoo.relayforge.endpoint.WebhookEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final JsonMapper jsonMapper;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;

    public EventService(EventRepository eventRepository, JsonMapper jsonMapper,
                        EndpointRepository endpointRepository, DeliveryRepository deliveryRepository) {
        this.eventRepository = eventRepository;
        this.jsonMapper = jsonMapper;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
    }

    public List<Event> findAll() {
        return eventRepository.findAll();
    }

    public Event findById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    @Transactional
    public Event create(String type, String payload, String idempotencyKey) {
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event type is required");
        }
        if (type.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event type must not exceed 255 characters");
        }
        if (payload == null || payload.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event payload is required");
        }

        try {
            jsonMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(payload);
        } catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event payload must be valid JSON");
        }

        Event event = new Event();
        event.setType(type);
        event.setPayload(payload);
        event.setIdempotencyKey(idempotencyKey);
        Event savedEvent = eventRepository.save(event);

        List<WebhookEndpoint> endpoints = endpointRepository.findSubscribedEndpoints(type);
        for (WebhookEndpoint endpoint : endpoints) {
            Delivery delivery = new Delivery();
            delivery.setEventId(savedEvent.getId());
            delivery.setEndpointId(endpoint.getId());
            deliveryRepository.save(delivery);
        }
        return savedEvent;
    }
}
