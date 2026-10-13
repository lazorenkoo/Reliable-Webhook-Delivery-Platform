package com.lazorenkoo.relayforge.endpoint;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    @Query("select endpoint from WebhookEndpoint endpoint join endpoint.subscribedEvents eventType "
            + "where endpoint.enabled = true and eventType = :eventType")
    List<WebhookEndpoint> findSubscribedEndpoints(@Param("eventType") String eventType);
}
