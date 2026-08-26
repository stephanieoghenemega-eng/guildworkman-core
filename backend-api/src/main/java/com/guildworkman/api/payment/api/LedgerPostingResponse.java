package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.LedgerDirection;
import com.guildworkman.api.payment.model.LedgerTransaction;
import com.guildworkman.api.payment.model.MinorUnits;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One journal entry with its lines — the audit view of what a payment did to
 * the books.
 *
 * @param totalAmount the posting's value, i.e. its debit total (which equals
 *                    its credit total; that is what balanced means)
 */
public record LedgerPostingResponse(
        String reference,
        String type,
        String currency,
        String narrative,
        BigDecimal totalAmount,
        Instant postedAt,
        List<Line> entries) {

    /** @param direction {@code DEBIT} or {@code CREDIT}; the amount is always a positive magnitude */
    public record Line(String accountCode, String direction, BigDecimal amount) {
    }

    public static LedgerPostingResponse from(LedgerTransaction posting) {
        String currency = posting.getCurrency();
        List<Line> lines = posting.getEntries().stream()
                .map(entry -> new Line(
                        entry.getAccount().getCode(),
                        entry.getDirection().name(),
                        MinorUnits.toMajor(entry.getAmountMinor(), currency)))
                .toList();
        return new LedgerPostingResponse(
                posting.getReference(),
                posting.getType().name(),
                currency,
                posting.getNarrative(),
                MinorUnits.toMajor(posting.totalMinor(LedgerDirection.DEBIT), currency),
                posting.getPostedAt(),
                lines);
    }
}
