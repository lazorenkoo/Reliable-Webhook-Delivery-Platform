package com.lazorenkoo.relayforge.delivery;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRetryPolicyTest {

    private final Instant completedAt = Instant.parse("2026-10-11T09:00:00Z");

    private Delivery failedDelivery(int attempts, Integer httpStatus) {
        Delivery delivery = new Delivery();
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setAttemptCount(attempts);
        delivery.setHttpStatus(httpStatus);
        delivery.setCompletedAt(completedAt);
        return delivery;
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void schedulesRetryAfterTemporaryHttpError(int httpStatus) {
        Delivery delivery = failedDelivery(1, httpStatus);

        DeliveryRetryPolicy.scheduleRetry(delivery);

        assertEquals(completedAt.plusSeconds(30), delivery.getNextAttemptAt());
    }

    @Test
    void increasesDelayAfterSecondNetworkFailure() {
        Delivery delivery = failedDelivery(2, null);

        DeliveryRetryPolicy.scheduleRetry(delivery);

        assertEquals(completedAt.plusSeconds(60), delivery.getNextAttemptAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404})
    void doesNotRetryPermanentHttpError(int httpStatus) {
        Delivery delivery = failedDelivery(1, httpStatus);
        delivery.setNextAttemptAt(completedAt.plusSeconds(30));

        DeliveryRetryPolicy.scheduleRetry(delivery);

        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void stopsAfterThirdAttempt() {
        Delivery delivery = failedDelivery(3, 500);
        delivery.setNextAttemptAt(completedAt.plusSeconds(60));

        DeliveryRetryPolicy.scheduleRetry(delivery);

        assertNull(delivery.getNextAttemptAt());
    }
}
