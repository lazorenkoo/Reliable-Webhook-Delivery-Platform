package com.lazorenkoo.relayforge.demo;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record DemoClient(String id, String name, String role, String mode, List<String> subscriptions,
                         Long endpointId, @JsonIgnore String secret, boolean active) {
    public boolean receives() {
        return role.equals("receiver") || role.equals("both");
    }

    public boolean sends() {
        return role.equals("sender") || role.equals("both");
    }
}
