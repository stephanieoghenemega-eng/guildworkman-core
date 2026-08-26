package com.guildworkman.api.payment.model;

/**
 * Which side of the ledger an entry lands on.
 *
 * <p>Amounts are always stored as a positive magnitude
 * ({@link LedgerEntry#getAmountMinor()}); the direction — never the sign —
 * carries the meaning. A negative amount is rejected at construction, so
 * "debit -500" can't be used as a backdoor way of expressing a credit and
 * quietly defeat the balance check.
 */
public enum LedgerDirection {
    DEBIT,
    CREDIT;

    public LedgerDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
