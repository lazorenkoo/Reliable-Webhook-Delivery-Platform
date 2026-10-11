package com.lazorenkoo.relayforge.delivery;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    List<Delivery> findByStatus(DeliveryStatus status);

    @Query("select delivery from Delivery delivery, WebhookEndpoint endpoint "
            + "where delivery.endpointId = endpoint.id and endpoint.enabled = true "
            + "and delivery.status = :status order by delivery.id")
    List<Delivery> findReadyDeliveries(DeliveryStatus status, Pageable pageable);

    List<Delivery> findByEventId(Long eventId);

    List<Delivery> findByEventIdAndStatus(Long eventId, DeliveryStatus status);
}
