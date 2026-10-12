package com.lazorenkoo.relayforge.endpoint;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookUrlPolicy {

    private final boolean allowLocal;

    public WebhookUrlPolicy(@Value("${relayforge.webhooks.allow-local:false}") boolean allowLocal) {
        this.allowLocal = allowLocal;
    }

    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid URL");
        }
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if ((!http && !https) || uri.getHost() == null) {
            throw invalid("URL must use HTTP or HTTPS and contain a host");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid("Webhook URL must not contain credentials or a fragment");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535 || (!allowLocal && port != -1 && port != (https ? 443 : 80))) {
            throw invalid("Webhook URL port is not allowed");
        }
        return uri;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
