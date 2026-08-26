package com.guildworkman.api.payment.model;

/**
 * Operator workflow state for a {@link ReconciliationDiscrepancy}.
 *
 * <p>Note what is missing: there is no state the <em>system</em> moves a
 * discrepancy into by fixing the books itself. Reconciliation reports; a
 * human decides. Auto-correcting a divergence is how an accounting bug
 * becomes invisible.
 */
public enum DiscrepancyStatus {

    /** Newly detected; nobody has looked at it. */
    OPEN,

    /** An operator has seen it and is working on it. */
    ACKNOWLEDGED,

    /** An operator has dealt with it — by posting a correcting journal entry, not by editing history. */
    RESOLVED
}
