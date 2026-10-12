package com.lazorenkoo.relayforge.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureTest {

    @Test
    void signatureDependsOnSecretTimestampAndExactBody() {
        String signature = WebhookSignature.sign("secret", "123", "{\"message\":\"Привет\"}");

        assertTrue(signature.matches("sha256=[0-9a-f]{64}"));
        assertNotEquals(signature, WebhookSignature.sign("other-secret", "123", "{\"message\":\"Привет\"}"));
        assertNotEquals(signature, WebhookSignature.sign("secret", "124", "{\"message\":\"Привет\"}"));
        assertNotEquals(signature, WebhookSignature.sign("secret", "123", "{\"message\":\"Привет\"} "));
        assertEquals(signature, WebhookSignature.sign("secret", "123", "{\"message\":\"Привет\"}"));
    }
}
