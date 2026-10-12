package com.lazorenkoo.relayforge.error;

public record ApiError(int status, String message, String path) {
}
