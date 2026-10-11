package com.lazorenkoo.relayforge.delivery;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

    List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(Long deliveryId);
}
