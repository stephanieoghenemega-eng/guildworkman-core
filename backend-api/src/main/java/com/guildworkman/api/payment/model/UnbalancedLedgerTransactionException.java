package com.guildworkman.api.payment.model;

/**
 * Raised when a journal entry's debits and credits do not agree. This is a
 * programming error in a posting rule, not a client error — it is surfaced as
 * a 500 by {@code GlobalExceptionHandler}'s fallback rather than being given
 * a client-facing problem type, because there is nothing a caller could
 * change about their request to avoid it.
 */
public class UnbalancedLedgerTransactionException extends RuntimeException {

    public UnbalancedLedgerTransactionException(String reference, String detail) {
        super("Unbalanced ledger posting reference=" + reference + ": " + detail);
    }
}
