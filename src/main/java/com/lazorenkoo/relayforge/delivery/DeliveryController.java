package com.lazorenkoo.relayforge.delivery;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostMapping
    public DeliveryResponse send(@RequestBody CreateDeliveryRequest request) {
        int httpStatus = deliveryService.send(request.getEventId(), request.getEndpointId());
        return new DeliveryResponse(httpStatus);
    }
}
