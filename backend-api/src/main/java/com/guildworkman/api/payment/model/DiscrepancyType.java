package com.guildworkman.api.payment.model;

/** What kind of divergence between the platform's books and the provider was found. */
public enum DiscrepancyType {

    /** The provider and the platform disagree about a payment's outcome. */
    PROVIDER_STATUS_DIVERGENCE,

    /** The provider and the platform agree on the outcome but not on the amount. */
    AMOUNT_DIVERGENCE,

    /**
     * A webhook could not legally be applied to the current state — see
     * {@link PaymentStatus} for why such an event is refused rather than
     * coerced.
     */
    ILLEGAL_TRANSITION,

    /** The provider reported on a reference the platform has no record of. */
    UNKNOWN_REFERENCE,

    /** The platform holds a payment the provider says it has never seen. */
    MISSING_PROVIDER_RECORD,

    /**
     * The book itself does not balance in some currency: total debits and
     * total credits disagree. Nothing short of a bug in a posting rule can
     * cause this, which is exactly why the sweep checks for it — a ledger
     * that has stopped balancing should not wait for someone to run a report.
     */
    LEDGER_IMBALANCE
}
