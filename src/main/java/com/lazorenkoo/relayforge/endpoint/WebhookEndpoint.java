package com.lazorenkoo.relayforge.endpoint;

public class WebhookEndpoint {

    private String url;
    private boolean enabled = true;

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
