package com.guildworkman.api.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The idempotency guard: one row per provider event this service has taken
 * responsibility for.
 *
 * <p><b>Why a database constraint and not an in-memory cache.</b> Paystack
 * retries deliveries, and the application runs as more than one instance
 * behind a load balancer — two retries can land on two JVMs at the same
 * instant. Only a unique index can atomically decide which of them owns the
 * event; a {@code Set} or a Caffeine cache decides nothing, it just makes the
 * race less likely to be observed in testing.
 *
 * <p><b>Why the row is written in the same transaction as the effect.</b>
 * Claiming the event in its own committed transaction first (the pattern
 * {@code EscrowOrchestrationInserter} uses, where the work is a long external
 * RPC call) would mean a crash between claim and effect permanently swallows
 * the event: the retry would be deduped against a claim whose work never
 * happened. Here the effect is a handful of local inserts, so the claim and
 * the effect commit or roll back together — a failure mid-processing releases
 * the event and Paystack's next retry processes it properly.
 *
 * <p><b>Why {@code eventKey} is derived rather than read from the payload.</b>
 * Paystack's webhook envelope carries {@code event} and {@code data} but no
 * dedicated delivery id, so there is no field to key on directly. See
 * {@code PaystackEventParser} for the derivation and its failure modes.
 */
@Entity
@Table(name = "processed_webhook_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_webhook_event_key", columnNames = "event_key"),
        indexes = @Index(name = "idx_processed_webhook_received", columnList = "received_at"))
@Getter
@Setter
@NoArgsConstructor
public class ProcessedWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, updatable = false, length = 200)
    private String eventKey;

    /** Paystack's {@code event} field, e.g. {@code charge.success}. */
    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    /** {@code Payment.reference} or {@code Payout.reference} the event named, when it named one. */
    @Column(name = "resource_reference", updatable = false, length = 128)
    private String resourceReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private WebhookOutcome outcome = WebhookOutcome.IGNORED;

    /** Why an event was rejected or ignored; null when it was applied cleanly. */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    public ProcessedWebhookEvent(String eventKey, String eventType, String resourceReference) {
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.resourceReference = resourceReference;
    }
}
