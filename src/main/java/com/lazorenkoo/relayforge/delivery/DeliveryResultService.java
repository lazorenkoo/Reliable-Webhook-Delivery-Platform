package com.lazorenkoo.relayforge.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        checkOwner(delivery);
        delivery.setProcessingStartedAt(null);
        deliveryRepository.save(delivery);
        attemptRepository.save(new DeliveryAttempt(delivery));
    }

    @Transactional
    public void finishManualRetry(Delivery delivery) {
        checkOwner(delivery);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setProcessingStartedAt(null);
        deliveryRepository.save(delivery);
    }

    private void checkOwner(Delivery delivery) {
        Delivery current = deliveryRepository.findForUpdate(delivery.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
        if (current.getStatus() != DeliveryStatus.PROCESSING
                || current.getProcessingVersion() != delivery.getProcessingVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery processing has changed");
        }
    }
}
