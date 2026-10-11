package com.lazorenkoo.relayforge.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryResultService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;

    public DeliveryResultService(DeliveryRepository deliveryRepository,
                                 DeliveryAttemptRepository attemptRepository) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
    }

    @Transactional
    public void save(Delivery delivery) {
        deliveryRepository.save(delivery);
        attemptRepository.save(new DeliveryAttempt(delivery));
    }
}
