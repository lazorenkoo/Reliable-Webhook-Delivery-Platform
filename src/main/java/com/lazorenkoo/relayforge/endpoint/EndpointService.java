package com.lazorenkoo.relayforge.endpoint;

import org.springframework.stereotype.Service;

@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;

    public EndpointService(EndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    public WebhookEndpoint create(String url) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl(url);
        return endpointRepository.save(endpoint);
    }
}
