package com.lazorenkoo.relayforge.event;

import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventCreationService {

    private final EventService eventService;
    private final EventRepository eventRepository;

    public EventCreationService(EventService eventService, EventRepository eventRepository) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
    }

    public Event create(String type, String payload, String key) {
        if (key == null) {
            return eventService.create(type, payload, null);
        }
        if (key.isBlank() || key.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key must contain 1 to 255 characters");
        }
        Event existing = eventRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            return checkRequest(existing, type, payload);
        }
        try {
            return eventService.create(type, payload, key);
        } catch (DataIntegrityViolationException exception) {
            Event winner = eventRepository.findByIdempotencyKey(key).orElse(null);
            if (winner == null) {
                throw exception;
            }
            return checkRequest(winner, type, payload);
        }
    }

    private Event checkRequest(Event event, String type, String payload) {
        if (!Objects.equals(event.getType(), type) || !Objects.equals(event.getPayload(), payload)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was used with different event data");
        }
        return event;
    }
}
