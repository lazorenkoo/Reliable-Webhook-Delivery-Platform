package com.lazorenkoo.relayforge.event;

import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public Event create(String type, String payload) {
        Event event = new Event();
        event.setType(type);
        event.setPayload(payload);
        return eventRepository.save(event);
    }
}
