package com.guildworkman.api.payment.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A verified, parsed Paystack webhook envelope.
 *
 * <p>Field access goes through the accessors below rather than reaching into
 * {@code data} at call sites, because Paystack does not use one name for one
 * concept: a charge's reference is {@code data.reference}, a refund's
 * <em>subject</em> is {@code data.transaction_reference}, and a transfer may
 * identify itself by {@code data.reference} or only by
 * {@code data.transfer_code}. Concentrating that here keeps the handlers
 * readable and keeps the quirks in one place to be corrected.
 *
 * @param type     Paystack's {@code event} field, e.g. {@code charge.success}
 * @param data     the {@code data} object, never null (an envelope without one is rejected at parse time)
 * @param eventKey the idempotency key derived for this delivery; see {@link PaystackEventParser}
 */
public record PaystackEvent(String type, JsonNode data, String eventKey) {

    /** The charge/transfer reference this event is about. */
    public String reference() {
        String reference = text("reference");
        return reference != null ? reference : text("transfer_code");
    }

    /**
     * The reference of the <em>charge</em> an event applies to. For a refund
     * that is {@code transaction_reference}; for a charge it is the charge's
     * own reference.
     */
    public String transactionReference() {
        String reference = text("transaction_reference");
        return reference != null ? reference : reference();
    }

    /** Paystack's numeric id for the underlying resource, when present. */
    public Long providerId() {
        return data.hasNonNull("id") ? data.get("id").asLong() : null;
    }

    /** Amount in minor units. Paystack always sends integer minor units here. */
    public long amountMinor() {
        return data.hasNonNull("amount") ? data.get("amount").asLong() : 0L;
    }

    /** Provider fee in minor units, when the event reports one. */
    public long feesMinor() {
        return data.hasNonNull("fees") ? data.get("fees").asLong() : 0L;
    }

    public String currency() {
        String currency = text("currency");
        return currency != null ? currency : "NGN";
    }

    /** The resource's own status string, e.g. {@code success}, {@code reversed}. */
    public String providerStatus() {
        return text("status");
    }

    /**
     * A human-readable failure cause. Paystack spells it differently per
     * resource: {@code gateway_response} on a charge, {@code reason} or
     * {@code message} on a transfer.
     */
    public String failureReason() {
        String reason = text("gateway_response");
        if (reason == null) {
            reason = text("reason");
        }
        if (reason == null) {
            reason = text("message");
        }
        return reason;
    }

    /**
     * A numeric value the platform put in {@code metadata} at initialization
     * and expects back on the event — the only channel Paystack gives for
     * correlating a transfer it reports with a worker this platform knows.
     */
    public Long metadataLong(String field) {
        JsonNode metadata = data.get("metadata");
        if (metadata == null || !metadata.hasNonNull(field)) {
            return null;
        }
        JsonNode value = metadata.get(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        // Paystack echoes metadata back as it was sent, and a client that set
        // it as a string gets a string back.
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(String field) {
        return data.hasNonNull(field) ? data.get(field).asText() : null;
    }
}
