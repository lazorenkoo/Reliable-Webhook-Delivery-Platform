package com.lazorenkoo.relayforge.delivery;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public List<Delivery> findAll(@RequestParam(required = false) DeliveryStatus status,
                                  @RequestParam(required = false) Long eventId) {
        return deliveryService.findAll(status, eventId);
    }

    @GetMapping("/{id}")
    public Delivery findById(@PathVariable Long id) {
        return deliveryService.findById(id);
    }

    @PostMapping
    public DeliveryResponse send(@RequestBody CreateDeliveryRequest request) {
        int httpStatus = deliveryService.send(request.getEventId(), request.getEndpointId());
        return new DeliveryResponse(httpStatus);
    }
}
