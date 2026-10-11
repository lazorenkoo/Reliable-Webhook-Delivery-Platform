package com.lazorenkoo.relayforge.delivery;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;

class DeliveryWorkerTest {

    private final DeliveryRepository repository = mock(DeliveryRepository.class);
    private final DeliveryService service = mock(DeliveryService.class);
    private final DeliveryWorker worker = new DeliveryWorker(repository, service);

    private Delivery delivery(Long id) {
        Delivery delivery = mock(Delivery.class);
        when(delivery.getId()).thenReturn(id);
        return delivery;
    }

    @Test
    void sendsSelectedPendingDeliveries() {
        List<Delivery> deliveries = List.of(delivery(1L), delivery(2L));
        when(repository.findReadyDeliveries(DeliveryStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(deliveries);

        worker.sendPendingDeliveries();

        verify(service).sendPending(1L);
        verify(service).sendPending(2L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void continuesAfterDeliveryFailure() {
        List<Delivery> deliveries = List.of(delivery(1L), delivery(2L));
        when(repository.findReadyDeliveries(DeliveryStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(deliveries);
        when(service.sendPending(1L)).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Webhook delivery failed"));

        worker.sendPendingDeliveries();

        verify(service).sendPending(1L);
        verify(service).sendPending(2L);
        verifyNoMoreInteractions(service);
    }
}
