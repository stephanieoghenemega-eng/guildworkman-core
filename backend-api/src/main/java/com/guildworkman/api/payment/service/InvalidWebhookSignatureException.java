package com.guildworkman.api.payment.service;

/**
 * Raised before a webhook payload is parsed, let alone applied. Rendered as a
 * 401 by {@code GlobalExceptionHandler} with a deliberately incurious detail
 * message: an attacker probing the endpoint learns only that the signature
 * was wrong, not which part of it or how close they were.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
