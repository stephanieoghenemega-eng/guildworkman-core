package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.MinorUnits;
import com.guildworkman.api.payment.service.LedgerService;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The books, summed. {@code balanced} is the invariant the whole ledger
 * exists to keep; {@code accountBalances} is what each account holds, each on
 * its own normal side so every figure reads as a positive number when
 * nothing is wrong.
 */
public record TrialBalanceResponse(
        String currency,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        boolean balanced,
        BigDecimal difference,
        Map<String, BigDecimal> accountBalances) {

    public static TrialBalanceResponse from(LedgerService.TrialBalance balance,
                                            Map<String, Long> accountBalancesMinor) {
        String currency = balance.currency();
        Map<String, BigDecimal> balances = new java.util.LinkedHashMap<>();
        accountBalancesMinor.forEach((code, minor) -> balances.put(code, MinorUnits.toMajor(minor, currency)));
        return new TrialBalanceResponse(
                currency,
                MinorUnits.toMajor(balance.debitsMinor(), currency),
                MinorUnits.toMajor(balance.creditsMinor(), currency),
                balance.balanced(),
                MinorUnits.toMajor(balance.differenceMinor(), currency),
                balances);
    }
}
