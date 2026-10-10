package com.lazorenkoo.relayforge.endpoint;

import java.util.LinkedHashSet;
import java.util.Set;

public class CreateEndpointRequest {

    private String url;
    private Set<String> subscribedEvents = new LinkedHashSet<>();

    public Set<String> getSubscribedEvents() {
        return subscribedEvents;
    }

    public void setSubscribedEvents(Set<String> subscribedEvents) {
        this.subscribedEvents = subscribedEvents;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
