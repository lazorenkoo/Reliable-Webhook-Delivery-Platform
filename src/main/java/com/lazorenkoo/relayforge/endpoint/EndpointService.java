package com.lazorenkoo.relayforge.endpoint;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;

    public EndpointService(EndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    public WebhookEndpoint create(String url, Set<String> subscribedEvents) {
        validateUrl(url);
        validateSubscriptions(subscribedEvents);

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl(url);
        endpoint.setSubscribedEvents(subscribedEvents);
        return endpointRepository.save(endpoint);
    }

    public List<WebhookEndpoint> findAll() {
        return endpointRepository.findAll();
    }

    public WebhookEndpoint findById(Long id) {
        return endpointRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found"));
    }

    public WebhookEndpoint disable(Long id) {
        WebhookEndpoint endpoint = findById(id);
        endpoint.setEnabled(false);
        return endpointRepository.save(endpoint);
    }

    public WebhookEndpoint enable(Long id) {
        WebhookEndpoint endpoint = findById(id);
        endpoint.setEnabled(true);
        return endpointRepository.save(endpoint);
    }

    public WebhookEndpoint updateSubscriptions(Long id, Set<String> subscribedEvents) {
        validateSubscriptions(subscribedEvents);
        WebhookEndpoint endpoint = findById(id);
        endpoint.setSubscribedEvents(subscribedEvents);
        return endpointRepository.save(endpoint);
    }

    private void validateSubscriptions(Set<String> subscribedEvents) {
        if (subscribedEvents == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscribed events are required");
        }
        for (String eventType : subscribedEvents) {
            if (eventType == null || eventType.isBlank() || eventType.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event types must contain 1 to 255 characters");
            }
        }
    }
    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL is required");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }

        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if ((!http && !https) || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL must use HTTP or HTTPS and contain a host");
        }
    }
}
