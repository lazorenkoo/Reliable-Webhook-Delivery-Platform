package com.lazorenkoo.relayforge.demo;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record DemoMessage(long id, String clientId, String clientName, Instant receivedAt,
                          JsonNode body, boolean signatureValid, String mode, int responseStatus) { }
