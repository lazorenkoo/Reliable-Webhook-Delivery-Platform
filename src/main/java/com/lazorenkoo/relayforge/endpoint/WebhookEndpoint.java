package com.lazorenkoo.relayforge.endpoint;

import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String url;

    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "endpoint_subscriptions", joinColumns = @JoinColumn(name = "endpoint_id"))
    @Column(name = "event_type", nullable = false)
    private Set<String> subscribedEvents = new LinkedHashSet<>();

    public Set<String> getSubscribedEvents() {
        return subscribedEvents;
    }

    public void setSubscribedEvents(Set<String> subscribedEvents) {
        this.subscribedEvents = new LinkedHashSet<>(subscribedEvents);
    }

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
