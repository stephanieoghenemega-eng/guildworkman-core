package com.guildworkman.api.payment.service;

/**
 * An outbound Paystack call failed — transport error, non-2xx response, or a
 * body that isn't the envelope the API documents.
 *
 * <p>Reconciliation treats this as "unknown", never as "the provider says
 * no". Reading a failed lookup as a missing record would let one Paystack
 * outage flood the discrepancy table with findings that say nothing about
 * the books.
 */
public class PaystackClientException extends RuntimeException {

    public PaystackClientException(String message) {
        super(message);
    }

    public PaystackClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
