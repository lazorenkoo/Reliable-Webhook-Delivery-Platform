package com.lazorenkoo.relayforge.delivery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.lazorenkoo.relayforge.endpoint.EndpointService;
import com.lazorenkoo.relayforge.endpoint.WebhookEndpoint;
import com.lazorenkoo.relayforge.event.Event;
import com.lazorenkoo.relayforge.event.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeliveryServiceTest {

    private EventService eventService;
    private EndpointService endpointService;
    private WebhookSender webhookSender;
    private DeliveryRepository deliveryRepository;
    private DeliveryService deliveryService;
    private List<DeliveryStatus> savedStatuses;
    private Delivery savedDelivery;

    @BeforeEach
    void setUp() {
        eventService = mock(EventService.class);
        endpointService = mock(EndpointService.class);
        webhookSender = mock(WebhookSender.class);
        deliveryRepository = mock(DeliveryRepository.class);
        deliveryService = new DeliveryService(eventService, endpointService, webhookSender, deliveryRepository);
        savedStatuses = new ArrayList<>();
    }

    private void prepareDelivery(boolean enabled) {
        Event event = new Event();
        event.setPayload("{\"orderId\":123}");
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl("https://example.com/webhook");
        endpoint.setEnabled(enabled);
        when(eventService.findById(1L)).thenReturn(event);
        when(endpointService.findById(2L)).thenReturn(endpoint);
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(invocation -> {
            savedDelivery = invocation.getArgument(0);
            if (savedDelivery.getStatus() == DeliveryStatus.PENDING) {
                assertNull(savedDelivery.getCompletedAt());
            } else {
                assertNotNull(savedDelivery.getCompletedAt());
            }
            savedStatuses.add(savedDelivery.getStatus());
            return savedDelivery;
        });
    }

    @Test
    void savesSuccessfulDelivery() throws Exception {
        prepareDelivery(true);
        when(webhookSender.send(anyString(), anyString())).thenReturn(204);

        assertEquals(204, deliveryService.send(1L, 2L));

        assertEquals(List.of(DeliveryStatus.PENDING, DeliveryStatus.SUCCEEDED), savedStatuses);
        assertEquals(204, savedDelivery.getHttpStatus());
        assertEquals(1L, savedDelivery.getEventId());
        assertEquals(2L, savedDelivery.getEndpointId());
        verify(webhookSender).send("https://example.com/webhook", "{\"orderId\":123}");
    }

    @Test
    void savesFailedDeliveryForServerError() throws Exception {
        prepareDelivery(true);
        when(webhookSender.send(anyString(), anyString())).thenReturn(500);

        assertEquals(500, deliveryService.send(1L, 2L));

        assertEquals(List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED), savedStatuses);
        assertEquals(500, savedDelivery.getHttpStatus());
    }

    @Test
    void savesFailedDeliveryForNetworkError() throws Exception {
        prepareDelivery(true);
        when(webhookSender.send(anyString(), anyString())).thenThrow(new IOException("Connection failed"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> deliveryService.send(1L, 2L));

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED), savedStatuses);
        assertNull(savedDelivery.getHttpStatus());
    }

    @Test
    void rejectsDisabledEndpointBeforeSending() {
        prepareDelivery(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> deliveryService.send(1L, 2L));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(webhookSender);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void rejectsMissingIdsBeforeLookingUpRecords() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> deliveryService.send(null, 2L));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(eventService, endpointService, webhookSender, deliveryRepository);
    }

    @Test
    void preservesInterruptionAndSavesFailure() throws Exception {
        prepareDelivery(true);
        when(webhookSender.send(anyString(), anyString())).thenThrow(new InterruptedException());

        try {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> deliveryService.send(1L, 2L));

            assertEquals(503, exception.getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED), savedStatuses);
            assertNull(savedDelivery.getHttpStatus());
        } finally {
            Thread.interrupted();
        }
    }
}
