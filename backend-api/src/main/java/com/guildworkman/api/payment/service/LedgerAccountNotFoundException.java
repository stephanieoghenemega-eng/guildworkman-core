package com.guildworkman.api.payment.service;

/**
 * A posting rule named an account that is not in the chart of accounts. Only
 * reachable if {@code LedgerAccountInitializer} failed to seed or someone
 * deleted a row by hand — either way the posting must fail rather than be
 * written against a substitute account.
 */
public class LedgerAccountNotFoundException extends RuntimeException {

    public LedgerAccountNotFoundException(String code) {
        super("Ledger account '" + code + "' is not in the chart of accounts");
    }
}
