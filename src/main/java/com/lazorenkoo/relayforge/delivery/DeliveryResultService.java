package com.lazorenkoo.relayforge.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

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
        delivery.setManualRetryProcessing(false);
        deliveryRepository.save(delivery);
        attemptRepository.save(new DeliveryAttempt(delivery));
    }

    @Transactional
    public void finishManualRetry(Delivery delivery) {
        checkOwner(delivery);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setProcessingStartedAt(null);
        delivery.setManualRetryProcessing(false);
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

    @Transactional
    public void recover(Long id, Instant cutoff) {
        Delivery delivery = deliveryRepository.findForUpdate(id).orElse(null);
        if (delivery == null || delivery.getStatus() != DeliveryStatus.PROCESSING) {
            return;
        }
        if (delivery.getProcessingStartedAt() != null && delivery.getProcessingStartedAt().isAfter(cutoff)) {
            return;
        }
        boolean manual = delivery.isManualRetryProcessing();
        delivery.setProcessingVersion(delivery.getProcessingVersion() + 1);
        delivery.setProcessingStartedAt(null);
        delivery.setManualRetryProcessing(false);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setNextAttemptAt(null);
        if (!manual) {
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setHttpStatus(null);
            delivery.setLastError("Processing expired before result was saved");
            delivery.setCompletedAt(Instant.now());
            DeliveryRetryPolicy.scheduleRetry(delivery);
            attemptRepository.save(new DeliveryAttempt(delivery));
        }
        deliveryRepository.save(delivery);
    }
}
