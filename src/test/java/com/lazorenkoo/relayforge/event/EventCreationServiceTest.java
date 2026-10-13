package com.lazorenkoo.relayforge.event;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventCreationServiceTest {

    private final EventService events = mock(EventService.class);
    private final EventRepository repository = mock(EventRepository.class);
    private final EventCreationService service = new EventCreationService(events, repository);

    @Test
    void returnsWinningEventAfterSaveConflict() {
        Event winner = new Event();
        winner.setType("order.created");
        winner.setPayload("{}");
        when(repository.findByIdempotencyKey("key"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(events.create("order.created", "{}", "key"))
                .thenThrow(new DataIntegrityViolationException("Duplicate key"));

        assertSame(winner, service.create("order.created", "{}", "key"));
        verify(repository, times(2)).findByIdempotencyKey("key");
        verify(events, times(1)).create("order.created", "{}", "key");
    }

    @Test
    void preservesSaveErrorWhenThereIsNoWinningEvent() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("Save failed");
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.empty());
        when(events.create("order.created", "{}", "key")).thenThrow(error);

        assertSame(error, assertThrows(DataIntegrityViolationException.class,
                () -> service.create("order.created", "{}", "key")));
    }
}
