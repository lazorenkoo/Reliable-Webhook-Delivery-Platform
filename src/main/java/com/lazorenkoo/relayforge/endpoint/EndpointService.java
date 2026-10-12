package com.lazorenkoo.relayforge.endpoint;

import java.util.List;
import java.util.Set;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final WebhookUrlPolicy urlPolicy;
    private final SecureRandom secureRandom = new SecureRandom();

    public EndpointService(EndpointRepository endpointRepository, WebhookUrlPolicy urlPolicy) {
        this.endpointRepository = endpointRepository;
        this.urlPolicy = urlPolicy;
    }

    public WebhookEndpoint create(String url, Set<String> subscribedEvents) {
        urlPolicy.validate(url);
        validateSubscriptions(subscribedEvents);

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl(url);
        endpoint.setSubscribedEvents(subscribedEvents);
        endpoint.setSigningSecret(generateSecret());
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

    public String rotateSecret(Long id) {
        WebhookEndpoint endpoint = findById(id);
        String secret = generateSecret();
        endpoint.setSigningSecret(secret);
        endpointRepository.save(endpoint);
        return secret;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
}
