package com.guildworkman.api.payment.model;

/**
 * Why a journal entry was posted. One provider event produces at most one
 * journal entry, so this doubles as the classification of the event that
 * caused it.
 */
public enum LedgerTransactionType {

    /** A client's card charge succeeded: money entered the system. */
    CHARGE_CAPTURE,

    /** All or part of a captured charge was returned to the client. */
    REFUND,

    /** Funds were transferred out to a skilled worker. */
    PAYOUT,

    /**
     * A previously-posted payout was undone by the provider (a failed or
     * reversed transfer). Posted as a new, opposite-signed journal entry —
     * the original {@link #PAYOUT} row is never touched.
     */
    PAYOUT_REVERSAL
}
