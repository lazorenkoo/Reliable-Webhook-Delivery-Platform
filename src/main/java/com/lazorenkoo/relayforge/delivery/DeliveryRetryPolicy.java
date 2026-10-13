package com.lazorenkoo.relayforge.delivery;

import java.time.Instant;

class DeliveryRetryPolicy {

    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 30;

    static void scheduleRetry(Delivery delivery) {
        delivery.setNextAttemptAt(null);
        if (delivery.getStatus() != DeliveryStatus.FAILED) {
            return;
        }
        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            return;
        }
        Integer httpStatus = delivery.getHttpStatus();
        if (httpStatus != null && httpStatus != 408 && httpStatus != 429 && httpStatus < 500) {
            return;
        }
        Instant completedAt = delivery.getCompletedAt();
        if (completedAt == null) {
            return;
        }
        long delay = RETRY_DELAY_SECONDS * delivery.getAttemptCount();
        delivery.setNextAttemptAt(completedAt.plusSeconds(delay));
    }
}
