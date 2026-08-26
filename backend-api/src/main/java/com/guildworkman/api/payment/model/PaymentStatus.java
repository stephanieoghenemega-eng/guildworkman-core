package com.guildworkman.api.payment.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an inbound charge, and the only place its legal transitions
 * are defined.
 *
 * <pre>
 *   INITIATED ──┬─> PENDING ──┬─> SUCCEEDED ──┬─> PARTIALLY_REFUNDED ──┬─> REFUNDED
 *               │             │               │          ^   │         │
 *               │             ├─> FAILED      │          └───┘         ├─> REVERSED
 *               │             └─> ABANDONED   ├─> REFUNDED             │
 *               ├─> SUCCEEDED                 └─> REVERSED  &lt;──────────┘
 *               ├─> FAILED
 *               └─> ABANDONED
 * </pre>
 *
 * <p><b>Why transitions are rejected rather than coerced.</b> Paystack does
 * not guarantee webhook ordering, and a retry can arrive minutes after the
 * event it duplicates. Two different-but-plausible policies exist for an
 * event that doesn't fit the current state: apply it anyway (last writer
 * wins) or refuse it. This ledger refuses it — a {@code refund.processed}
 * that arrives before the {@code charge.success} it refunds would, if
 * applied, post a refund against money the books say was never collected,
 * and the ledger would balance while describing something that did not
 * happen. Refusing turns that into an {@code IllegalPaymentTransitionException},
 * which {@code PaystackWebhookService} records as a reconciliation
 * discrepancy for an operator. See docs/PAYMENTS_LEDGER.md, "Out-of-order
 * events".
 *
 * <p><b>Why {@code SUCCEEDED -> SUCCEEDED} is illegal.</b> A redelivery of
 * the same event never reaches here at all — {@code ProcessedWebhookEvent}
 * stops it first. So a <em>second, distinct</em> success event for a payment
 * already marked succeeded is not a retry; it is the provider telling us
 * something we cannot reconcile, and it is flagged. The one self-transition
 * that is legal is {@link #PARTIALLY_REFUNDED} to itself, because a charge
 * can legitimately be refunded in several instalments.
 */
public enum PaymentStatus {

    /** Recorded locally; the client has been handed a checkout URL. */
    INITIATED,

    /** The provider has acknowledged the attempt but not settled it. */
    PENDING,

    /** Funds captured. The only state from which money can be refunded. */
    SUCCEEDED,

    /** The provider declined the charge. Terminal; no money moved. */
    FAILED,

    /** The client never completed checkout and the provider timed it out. Terminal. */
    ABANDONED,

    /** Part of the captured amount has been returned; more may follow. */
    PARTIALLY_REFUNDED,

    /** The full captured amount has been returned. Terminal. */
    REFUNDED,

    /** The provider clawed the charge back (chargeback/reversal). Terminal. */
    REVERSED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL = new EnumMap<>(PaymentStatus.class);

    static {
        LEGAL.put(INITIATED, EnumSet.of(PENDING, SUCCEEDED, FAILED, ABANDONED));
        LEGAL.put(PENDING, EnumSet.of(SUCCEEDED, FAILED, ABANDONED));
        LEGAL.put(SUCCEEDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED, REVERSED));
        LEGAL.put(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED, REVERSED));
        LEGAL.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        LEGAL.put(ABANDONED, EnumSet.noneOf(PaymentStatus.class));
        LEGAL.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        LEGAL.put(REVERSED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return LEGAL.get(this).contains(next);
    }

    /** @return the states reachable from this one, for documentation and error messages. */
    public Set<PaymentStatus> allowedNext() {
        return Set.copyOf(LEGAL.get(this));
    }

    /** True once no further transition is possible. */
    public boolean isTerminal() {
        return LEGAL.get(this).isEmpty();
    }

    /** True once money has actually been captured, i.e. the ledger holds entries for it. */
    public boolean isCaptured() {
        return this == SUCCEEDED || this == PARTIALLY_REFUNDED || this == REFUNDED || this == REVERSED;
    }
}
