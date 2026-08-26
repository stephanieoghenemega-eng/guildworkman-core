package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.model.PayoutStatus;

/**
 * A state change was refused because the lifecycle does not allow it — see
 * {@link PaymentStatus} for why refusing beats coercing.
 *
 * <p>On the webhook path this is caught and turned into a recorded
 * discrepancy plus a 200, because retrying will never make the transition
 * legal. Raised from anywhere else it surfaces as a 409 through
 * {@code GlobalExceptionHandler}: the request was well-formed, it just
 * conflicts with the resource's current state.
 */
public class IllegalPaymentTransitionException extends RuntimeException {

    private final String resourceReference;
    private final String from;
    private final String to;

    public IllegalPaymentTransitionException(String resourceReference, PaymentStatus from, PaymentStatus to) {
        this(resourceReference, from.name(), to.name(), from.allowedNext().toString());
    }

    public IllegalPaymentTransitionException(String resourceReference, PayoutStatus from, PayoutStatus to) {
        this(resourceReference, from.name(), to.name(), from.allowedNext().toString());
    }

    private IllegalPaymentTransitionException(String resourceReference, String from, String to, String allowed) {
        super("Cannot move " + resourceReference + " from " + from + " to " + to + "; allowed: " + allowed);
        this.resourceReference = resourceReference;
        this.from = from;
        this.to = to;
    }

    public String getResourceReference() {
        return resourceReference;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
