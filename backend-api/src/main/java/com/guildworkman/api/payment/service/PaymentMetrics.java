package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.DiscrepancyType;
import com.guildworkman.api.payment.model.WebhookOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Micrometer counters for the money path, scraped at
 * {@code /actuator/prometheus}. Same reasoning as
 * {@link com.guildworkman.api.signing.service.SigningMetrics}: the failures
 * worth paging on here are the silent ones.
 *
 * <p>A rotated secret that was never redeployed does not throw anywhere an
 * operator is looking — it just rejects every webhook, and the first visible
 * symptom is that captures stopped some hours ago. A provider outage looks
 * identical to "no payments today". A reconciliation sweep that throws on its
 * own scheduled thread disappears entirely. Each of those has a counter here.
 *
 * <p><b>Tag values are closed sets.</b> The event type is the one piece of
 * caller-supplied data that is tempting to tag with, and tagging it directly
 * would let anyone who can reach the webhook mint unbounded time series by
 * posting new type strings. {@link #eventType} collapses anything outside the
 * handled set to {@code other}, so the cardinality of these series is fixed by
 * this file rather than by traffic. No reference, event key or account is ever
 * a tag — those belong in logs, which is where the cross-reference to a
 * discrepancy id lives.
 */
@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    static final String WEBHOOK_EVENTS = "payments.webhook.events";
    static final String SIGNATURE_FAILURES = "payments.webhook.signature.failures";
    static final String DISCREPANCIES = "payments.reconciliation.discrepancies";
    static final String SWEEPS = "payments.reconciliation.sweeps";
    static final String PROVIDER_UNREACHABLE = "payments.provider.unreachable";

    /** Why a webhook was refused before it reached a parser. */
    public enum SignatureFailure {
        /** No secret configured — every webhook is being dropped. Alert on any. */
        NOT_CONFIGURED,
        /** Caller sent no {@code x-paystack-signature} header. */
        MISSING_HEADER,
        /** Signed header present but the body was empty. */
        EMPTY_BODY,
        /** The MAC did not match. A sustained rate here is someone probing. */
        MISMATCH
    }

    private static final Set<String> HANDLED_TYPES = Set.of(
            PaystackEventTypes.CHARGE_SUCCESS,
            PaystackEventTypes.CHARGE_FAILED,
            PaystackEventTypes.REFUND_PROCESSED,
            PaystackEventTypes.REFUND_FAILED,
            PaystackEventTypes.TRANSFER_SUCCESS,
            PaystackEventTypes.TRANSFER_FAILED,
            PaystackEventTypes.TRANSFER_REVERSED);

    private final MeterRegistry registry;

    /** One per event that got past signature verification. */
    public void webhookEvent(String type, WebhookOutcome outcome) {
        registry.counter(WEBHOOK_EVENTS, "type", eventType(type), "outcome", outcome.name()).increment();
    }

    public void signatureFailure(SignatureFailure reason) {
        registry.counter(SIGNATURE_FAILURES, "reason", reason.name()).increment();
    }

    /** A finding that was actually filed; deduplicated repeats do not count. */
    public void discrepancyRecorded(DiscrepancyType type) {
        registry.counter(DISCREPANCIES, "type", type.name()).increment();
    }

    /**
     * @param failed true if the sweep threw. A scheduled method that throws is
     *               logged by Spring and otherwise invisible, so it gets a
     *               counter of its own rather than sharing "examined nothing".
     */
    public void sweepCompleted(boolean failed) {
        registry.counter(SWEEPS, "outcome", failed ? "failed" : "completed").increment();
    }

    /** Paystack could not be reached for one payment. Not a divergence. */
    public void providerUnreachable() {
        registry.counter(PROVIDER_UNREACHABLE).increment();
    }

    /** Bounds the {@code type} tag to the handled set plus {@code other}. */
    private static String eventType(String type) {
        return type != null && HANDLED_TYPES.contains(type) ? type : "other";
    }
}
