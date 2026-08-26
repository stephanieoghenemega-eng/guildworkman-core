package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.LedgerDirection;
import com.guildworkman.api.payment.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Read-side of the journal. There is deliberately no update or delete query
 * here — see {@link com.guildworkman.api.payment.model.LedgerTransaction} on
 * why the ledger is append-only.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * Total for one side of the whole book, in one currency. The trial-balance
     * invariant is that this returns the same figure for {@code DEBIT} and
     * {@code CREDIT}; {@code LedgerService.trialBalance} asserts exactly that,
     * and an integration test asserts it after every scenario the suite runs.
     *
     * <p>{@code coalesce} because {@code sum} over no rows is null in SQL, and
     * an empty book should read as zero rather than blow up on unboxing.
     */
    /**
     * Sums the whole journal for one currency and side. Backed by
     * {@code idx_ledger_entry_currency_direction}.
     *
     * <p><b>This is an unbounded aggregate and it grows with the ledger.</b>
     * At the scale this platform is at — thousands of entries a day, four
     * entries per capture — an indexed aggregate is milliseconds and the
     * simplicity is worth more than the alternative. It is deliberately not
     * paginated, because a partial sum is not a trial balance: the whole point
     * is to compare the totals of both sides, and summing half the journal
     * would report an imbalance that isn't there.
     *
     * <p>The scale to watch is roughly <b>10 million entries</b> (order of a
     * few years at ~10k captures/day), where this starts to be seconds rather
     * than milliseconds and the reconciliation sweep's runtime becomes
     * noticeable. The fix at that point is a rolling balance snapshot — a
     * materialized per-account running total, closed periodically, with the
     * aggregate only summing entries since the last close. That is a real
     * design change (it introduces a derived number that can itself be wrong)
     * and should not be added before it is needed.
     */
    @Query("select coalesce(sum(e.amountMinor), 0) from LedgerEntry e "
            + "where e.direction = :direction and e.currency = :currency")
    long totalByDirection(@Param("direction") LedgerDirection direction, @Param("currency") String currency);

    @Query("select coalesce(sum(e.amountMinor), 0) from LedgerEntry e "
            + "where e.account.code = :accountCode and e.direction = :direction and e.currency = :currency")
    long totalByAccountAndDirection(@Param("accountCode") String accountCode,
                                    @Param("direction") LedgerDirection direction,
                                    @Param("currency") String currency);

    @Query("select distinct e.currency from LedgerEntry e")
    List<String> findDistinctCurrencies();

    List<LedgerEntry> findByLedgerTransactionIdOrderByIdAsc(Long ledgerTransactionId);
}
