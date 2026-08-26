package com.guildworkman.api.payment.model;

/**
 * The five classical account categories, reduced to the four this ledger
 * actually needs (there is no equity account — the platform's own capital is
 * out of scope for a payments ledger).
 *
 * <p>An account's <em>normal balance</em> is the side that increases it:
 * assets and expenses increase on the debit side, liabilities and revenue on
 * the credit side. {@link #normalBalance()} exists so a balance report can
 * present "how much is in this account" as a positive number regardless of
 * side, rather than making every reader remember the convention.
 */
public enum LedgerAccountType {

    /** Something the platform owns or is owed — e.g. funds sitting at Paystack. */
    ASSET(LedgerDirection.DEBIT),

    /** Something the platform owes — e.g. money held on a worker's behalf. */
    LIABILITY(LedgerDirection.CREDIT),

    /** Income the platform has earned — e.g. the marketplace commission. */
    REVENUE(LedgerDirection.CREDIT),

    /** Cost the platform has incurred — e.g. the provider's processing fee. */
    EXPENSE(LedgerDirection.DEBIT);

    private final LedgerDirection normalBalance;

    LedgerAccountType(LedgerDirection normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** The side on which a balance of this account type increases. */
    public LedgerDirection normalBalance() {
        return normalBalance;
    }
}
