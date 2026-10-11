package com.lazorenkoo.relayforge.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

@Service
public class DeliveryClaimService {

    private final DeliveryRepository repository;

    public DeliveryClaimService(DeliveryRepository repository) {
        this.repository = repository;
    }

    public void claim(Delivery delivery, DeliveryStatus expected) {
        if (expected != DeliveryStatus.PENDING && expected != DeliveryStatus.FAILED) {
            throw new IllegalArgumentException("Only pending or failed deliveries can be claimed");
        }
        if (repository.claim(delivery.getId(), expected, DeliveryStatus.PROCESSING,
                delivery.getAttemptCount()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is already being processed or completed");
        }
        delivery.setStatus(DeliveryStatus.PROCESSING);
    }

    public void claimRetry(Delivery delivery) {
        if (repository.claimRetry(delivery.getId(), DeliveryStatus.FAILED, DeliveryStatus.PROCESSING,
                delivery.getNextAttemptAt(), Instant.now(), delivery.getAttemptCount()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is not ready for retry");
        }
        delivery.setStatus(DeliveryStatus.PROCESSING);
    }
}
