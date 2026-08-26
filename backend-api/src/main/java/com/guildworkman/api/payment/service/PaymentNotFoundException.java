package com.guildworkman.api.payment.service;

/** No payment exists for the given reference. Rendered as a 404. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String reference) {
        super("No payment found for reference " + reference);
    }
}
