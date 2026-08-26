package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, Long> {

    Optional<ProcessedWebhookEvent> findByEventKey(String eventKey);

    boolean existsByEventKey(String eventKey);
}
