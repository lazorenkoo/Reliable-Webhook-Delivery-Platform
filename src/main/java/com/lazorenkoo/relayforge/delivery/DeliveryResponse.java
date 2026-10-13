package com.lazorenkoo.relayforge.delivery;

public class DeliveryResponse {

    private final int httpStatus;

    public DeliveryResponse(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isSuccessful() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
