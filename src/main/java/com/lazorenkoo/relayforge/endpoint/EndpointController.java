package com.lazorenkoo.relayforge.endpoint;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @GetMapping
    public List<WebhookEndpoint> findAll() {
        return endpointService.findAll();
    }

    @GetMapping("/{id}")
    public WebhookEndpoint findById(@PathVariable Long id) {
        return endpointService.findById(id);
    }

    @PatchMapping("/{id}/disable")
    public WebhookEndpoint disable(@PathVariable Long id) {
        return endpointService.disable(id);
    }

    @PatchMapping("/{id}/enable")
    public WebhookEndpoint enable(@PathVariable Long id) {
        return endpointService.enable(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpoint create(@RequestBody CreateEndpointRequest request) {
        return endpointService.create(request.getUrl());
    }
}
