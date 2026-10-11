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
        Instant now = Instant.now();
        if (repository.claim(delivery.getId(), expected, DeliveryStatus.PROCESSING,
                delivery.getAttemptCount(), delivery.getProcessingVersion(), now) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is already being processed or completed");
        }
        delivery.setStatus(DeliveryStatus.PROCESSING);
        delivery.setProcessingVersion(delivery.getProcessingVersion() + 1);
        delivery.setProcessingStartedAt(now);
    }

    public void claimRetry(Delivery delivery) {
        Instant now = Instant.now();
        if (repository.claimRetry(delivery.getId(), DeliveryStatus.FAILED, DeliveryStatus.PROCESSING,
                delivery.getNextAttemptAt(), now, delivery.getAttemptCount(), delivery.getProcessingVersion()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is not ready for retry");
        }
        delivery.setStatus(DeliveryStatus.PROCESSING);
        delivery.setProcessingVersion(delivery.getProcessingVersion() + 1);
        delivery.setProcessingStartedAt(now);
    }
}
