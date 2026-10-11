package com.lazorenkoo.relayforge.delivery;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long eventId;
    private Long endpointId;
    private Integer httpStatus;
    private Instant createdAt;
    private Instant completedAt;
    private Integer attemptCount = 0;
    private Instant nextAttemptAt;
    private Instant processingStartedAt;
    private Long processingVersion = 0L;
    private Boolean manualRetryProcessing = false;

    public boolean isManualRetryProcessing() {
        return Boolean.TRUE.equals(manualRetryProcessing);
    }

    public void setManualRetryProcessing(boolean manualRetryProcessing) {
        this.manualRetryProcessing = manualRetryProcessing;
    }

    @Column(columnDefinition = "text")
    private String lastError;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @PrePersist
    private void onCreate() {
        createdAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(Instant processingStartedAt) {
        this.processingStartedAt = processingStartedAt;
    }

    public long getProcessingVersion() {
        return processingVersion == null ? 0 : processingVersion;
    }

    public void setProcessingVersion(long processingVersion) {
        if (processingVersion < 0) {
            throw new IllegalArgumentException("Processing version must not be negative");
        }
        this.processingVersion = processingVersion;
    }

    public int getAttemptCount() {
        return attemptCount == null ? 0 : attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        if (attemptCount < 0) {
            throw new IllegalArgumentException("Attempt count must not be negative");
        }
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public Long getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(Long endpointId) {
        this.endpointId = endpointId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }
}
