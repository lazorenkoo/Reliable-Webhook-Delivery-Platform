package com.lazorenkoo.relayforge.endpoint;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class BlockedWebhookUrlException extends ResponseStatusException {

    public BlockedWebhookUrlException(String reason) {
        super(HttpStatus.BAD_REQUEST, reason);
    }
}
