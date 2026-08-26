package com.guildworkman.api.payment.service;

/**
 * The Paystack webhook event names this service acts on. Anything else is
 * recorded as {@link com.guildworkman.api.payment.model.WebhookOutcome#IGNORED}
 * rather than refused — Paystack enables event types per account and adds new
 * ones over time, and a 4xx on an event we simply don't care about would put
 * the endpoint into the provider's retry-then-disable path for no reason.
 */
public final class PaystackEventTypes {

    public static final String CHARGE_SUCCESS = "charge.success";
    public static final String CHARGE_FAILED = "charge.failed";

    public static final String REFUND_PROCESSED = "refund.processed";
    public static final String REFUND_FAILED = "refund.failed";

    public static final String TRANSFER_SUCCESS = "transfer.success";
    public static final String TRANSFER_FAILED = "transfer.failed";
    public static final String TRANSFER_REVERSED = "transfer.reversed";

    private PaystackEventTypes() {
    }
}
