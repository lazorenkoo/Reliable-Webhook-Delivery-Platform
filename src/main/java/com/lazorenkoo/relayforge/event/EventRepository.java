package com.lazorenkoo.relayforge.event;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdempotencyKey(String idempotencyKey);
}
