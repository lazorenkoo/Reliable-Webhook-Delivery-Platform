package com.lazorenkoo.relayforge.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryClaimService {

    private final DeliveryRepository repository;

    public DeliveryClaimService(DeliveryRepository repository) {
        this.repository = repository;
    }

    public void claim(Long id, DeliveryStatus expected) {
        if (expected != DeliveryStatus.PENDING && expected != DeliveryStatus.FAILED) {
            throw new IllegalArgumentException("Only pending or failed deliveries can be claimed");
        }
        if (repository.claim(id, expected, DeliveryStatus.PROCESSING) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is already being processed or completed");
        }
    }
}
