package com.lazorenkoo.relayforge.delivery;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "relayforge.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryWorker.class);
    private final DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;

    public DeliveryWorker(DeliveryRepository deliveryRepository, DeliveryService deliveryService) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${relayforge.worker.delay:5000}")
    public void sendPendingDeliveries() {
        for (Delivery delivery : deliveryRepository.findReadyDeliveries(
                DeliveryStatus.PENDING, PageRequest.of(0, 20))) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                deliveryService.sendPending(delivery.getId());
            } catch (ResponseStatusException exception) {
                logger.warn("Delivery {} could not be sent: {}", delivery.getId(), exception.getReason());
            }
        }
        for (Delivery delivery : deliveryRepository.findDueRetries(
                DeliveryStatus.FAILED, Instant.now(), PageRequest.of(0, 20))) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                deliveryService.sendScheduledRetry(delivery.getId());
            } catch (ResponseStatusException exception) {
                logger.warn("Delivery {} retry failed: {}", delivery.getId(), exception.getReason());
            }
        }
    }
}
