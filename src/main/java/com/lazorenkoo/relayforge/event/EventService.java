package com.lazorenkoo.relayforge.event;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final JsonMapper jsonMapper;

    public EventService(EventRepository eventRepository, JsonMapper jsonMapper) {
        this.eventRepository = eventRepository;
        this.jsonMapper = jsonMapper;
    }

    public List<Event> findAll() {
        return eventRepository.findAll();
    }

    public Event findById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    public Event create(String type, String payload) {
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
        return eventRepository.save(event);
    }
}
