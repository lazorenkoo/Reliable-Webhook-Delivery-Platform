package com.lazorenkoo.relayforge.delivery;

import java.util.List;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    List<Delivery> findByStatus(DeliveryStatus status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Delivery delivery set delivery.status = :processing "
            + "where delivery.id = :id and delivery.status = :expected")
    int claim(Long id, DeliveryStatus expected, DeliveryStatus processing);

    @Query("select delivery from Delivery delivery, WebhookEndpoint endpoint "
            + "where delivery.endpointId = endpoint.id and endpoint.enabled = true "
            + "and delivery.status = :status order by delivery.id")
    List<Delivery> findReadyDeliveries(DeliveryStatus status, Pageable pageable);

    @Query("select delivery from Delivery delivery, WebhookEndpoint endpoint "
            + "where delivery.endpointId = endpoint.id and endpoint.enabled = true "
            + "and delivery.status = :status and delivery.nextAttemptAt <= :now "
            + "and delivery.attemptCount < 3 order by delivery.nextAttemptAt, delivery.id")
    List<Delivery> findDueRetries(DeliveryStatus status, Instant now, Pageable pageable);

    List<Delivery> findByEventId(Long eventId);

    List<Delivery> findByEventIdAndStatus(Long eventId, DeliveryStatus status);
}
