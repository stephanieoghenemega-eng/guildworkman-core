package com.guildworkman.api.payment.model;

import java.util.List;

/**
 * The platform's chart of accounts for the fiat leg.
 *
 * <p>Kept as a small fixed set rather than an operator-managed table: every
 * posting rule in {@code LedgerService} names these codes directly,
 * so an account that isn't referenced by a posting rule could never receive
 * an entry anyway. Adding an account is therefore always a code change that
 * arrives together with the rule that uses it.
 *
 * <p>The on-chain (Stellar/Soroban) leg is tracked separately — see
 * {@code com.guildworkman.api.escrow} — and would get its own accounts here
 * when the two legs are consolidated into one set of books. Nothing in this
 * model prevents that: the codes are namespaced by provider where the
 * distinction matters ({@link #PROVIDER_RECEIVABLE_PAYSTACK}).
 */
public final class LedgerAccounts {

    /**
     * Funds Paystack holds on the platform's behalf: increased by a
     * successful charge, decreased by a payout or a refund. This is not the
     * platform's bank balance — it becomes that only on settlement, which
     * this PR does not model (see docs/PAYMENTS_LEDGER.md, "Follow-ups").
     */
    public static final String PROVIDER_RECEIVABLE_PAYSTACK = "PROVIDER_RECEIVABLE_PAYSTACK";

    /** Money collected from a client that the platform owes a skilled worker. */
    public static final String WORKER_PAYABLE = "WORKER_PAYABLE";

    /** The platform's commission on a charge — see {@code PaymentProperties#platformFeeBps}. */
    public static final String PLATFORM_FEE_REVENUE = "PLATFORM_FEE_REVENUE";

    /**
     * Paystack's own processing/transfer fee, taken out of the gross amount
     * before it ever reaches the platform. Recorded as an expense so the
     * gross charge and the net receivable both stay visible — netting it
     * silently would make the books disagree with the client's receipt.
     */
    public static final String PROVIDER_FEE_EXPENSE = "PROVIDER_FEE_EXPENSE";

    private LedgerAccounts() {
    }

    /** Seeded on startup by {@code LedgerAccountInitializer}. */
    public static List<LedgerAccount> chartOfAccounts() {
        return List.of(
                new LedgerAccount(PROVIDER_RECEIVABLE_PAYSTACK, LedgerAccountType.ASSET,
                        "Funds held by Paystack on the platform's behalf"),
                new LedgerAccount(WORKER_PAYABLE, LedgerAccountType.LIABILITY,
                        "Collected funds owed to skilled workers"),
                new LedgerAccount(PLATFORM_FEE_REVENUE, LedgerAccountType.REVENUE,
                        "Platform commission earned on charges"),
                new LedgerAccount(PROVIDER_FEE_EXPENSE, LedgerAccountType.EXPENSE,
                        "Processing fees charged by Paystack"));
    }
}
