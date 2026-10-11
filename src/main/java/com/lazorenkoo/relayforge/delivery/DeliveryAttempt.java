package com.lazorenkoo.relayforge.delivery;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long deliveryId;
    private int attemptNumber;
    private Integer httpStatus;
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;

    @Column(columnDefinition = "text")
    private String error;

    protected DeliveryAttempt() {
    }

    public DeliveryAttempt(Delivery delivery) {
        deliveryId = delivery.getId();
        attemptNumber = delivery.getAttemptCount();
        httpStatus = delivery.getHttpStatus();
        completedAt = delivery.getCompletedAt();
        status = delivery.getStatus();
        error = delivery.getLastError();
    }

    public Long getId() {
        return id;
    }

    public Long getDeliveryId() {
        return deliveryId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
