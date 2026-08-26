package com.guildworkman.api.payment.service;

/**
 * The signature checked out but the body is not a webhook envelope this
 * service can read — no {@code event}, no {@code data}, or not JSON at all.
 *
 * <p>Answered with 400 rather than swallowed with a 200. A correctly-signed
 * payload we cannot parse means either Paystack changed the envelope or
 * something is very wrong with the account's configuration, and neither is
 * improved by quietly acknowledging it: the delivery failing in Paystack's
 * own dashboard is itself the actionable signal.
 */
public class MalformedWebhookPayloadException extends RuntimeException {

    public MalformedWebhookPayloadException(String message) {
        super(message);
    }

    public MalformedWebhookPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
