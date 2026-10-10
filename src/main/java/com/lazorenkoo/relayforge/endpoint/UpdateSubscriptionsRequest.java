package com.lazorenkoo.relayforge.endpoint;

import java.util.Set;

public class UpdateSubscriptionsRequest {

    private Set<String> subscribedEvents;

    public Set<String> getSubscribedEvents() {
        return subscribedEvents;
    }

    public void setSubscribedEvents(Set<String> subscribedEvents) {
        this.subscribedEvents = subscribedEvents;
    }
}
