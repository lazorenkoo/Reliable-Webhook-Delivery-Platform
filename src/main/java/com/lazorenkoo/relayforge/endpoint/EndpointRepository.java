package com.lazorenkoo.relayforge.endpoint;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
}
