package com.guildworkman.api.payment.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an outbound transfer to a skilled worker.
 *
 * <pre>
 *   PENDING ──┬─> PAID ──> REVERSED
 *             └─> FAILED
 * </pre>
 *
 * <p>{@code PENDING -> REVERSED} is deliberately illegal: a transfer that was
 * never reported as paid cannot be reversed, so a {@code transfer.reversed}
 * arriving while the payout is still pending means either the
 * {@code transfer.success} was lost or the provider is describing a payout
 * this platform does not know about. Both are exactly the divergence the
 * reconciliation job exists to surface, so the event is rejected and
 * recorded rather than being applied as a state jump that would leave the
 * ledger holding a reversal for a payout it never posted.
 *
 * <p>Payout rows are created by {@code transfer.*} webhooks rather than by an
 * initiation endpoint — this PR does not call Paystack's Transfer API, which
 * needs transfer-recipient management that belongs with its own issue. See
 * docs/PAYMENTS_LEDGER.md, "Follow-ups".
 */
public enum PayoutStatus {

    /** Known to the platform, not yet confirmed paid by the provider. */
    PENDING,

    /** The provider confirmed the transfer landed. */
    PAID,

    /** The provider could not complete the transfer. Terminal; funds stay with the platform. */
    FAILED,

    /** A previously-paid transfer was clawed back. Terminal. */
    REVERSED;

    private static final Map<PayoutStatus, Set<PayoutStatus>> LEGAL = new EnumMap<>(PayoutStatus.class);

    static {
        LEGAL.put(PENDING, EnumSet.of(PAID, FAILED));
        LEGAL.put(PAID, EnumSet.of(REVERSED));
        LEGAL.put(FAILED, EnumSet.noneOf(PayoutStatus.class));
        LEGAL.put(REVERSED, EnumSet.noneOf(PayoutStatus.class));
    }

    public boolean canTransitionTo(PayoutStatus next) {
        return LEGAL.get(this).contains(next);
    }

    public Set<PayoutStatus> allowedNext() {
        return Set.copyOf(LEGAL.get(this));
    }

    public boolean isTerminal() {
        return LEGAL.get(this).isEmpty();
    }
}
