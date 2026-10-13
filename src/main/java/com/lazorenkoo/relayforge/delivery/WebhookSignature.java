package com.lazorenkoo.relayforge.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class WebhookSignature {

    public static String sign(String secret, String timestamp, String payload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Signing secret is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign webhook", exception);
        }
    }
}
