package com.guildworkman.api.payment.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One balanced journal entry: a set of {@link LedgerEntry} lines whose debits
 * equal their credits, posted atomically.
 *
 * <p><b>Append-only.</b> Nothing in the application ever updates a posted
 * journal entry. A charge that is later refunded, or a payout the provider
 * reverses, is corrected by posting a <em>new</em> opposite-signed entry
 * (see {@link LedgerTransactionType#PAYOUT_REVERSAL}), leaving the original
 * intact — that is the whole point of an accounting ledger, and it is what
 * makes {@code Transaction}/{@code TransactionHistory} safe to derive from
 * it rather than maintain in parallel. Two mechanisms enforce it:
 * <ul>
 *   <li>every column is {@code updatable = false} and neither this class nor
 *       {@link LedgerEntry} exposes a setter, so there is no in-process path
 *       to a mutation in the first place;</li>
 *   <li>{@link #rejectUpdate()} turns any mutation that somehow reaches a
 *       flush into a loud failure rather than a silently dropped write.</li>
 * </ul>
 * Deletes are deliberately <em>not</em> blocked here. No application code
 * path deletes a ledger row, but the test suite has to reset the schema
 * between classes, and a JPA callback is the wrong place to enforce a
 * guarantee that belongs to database privileges anyway — production should
 * withhold {@code DELETE} on {@code ledger_transactions}/{@code ledger_entries}
 * from the application role (see docs/PAYMENTS_LEDGER.md, "Operations").
 *
 * <p><b>The balance invariant is enforced by the aggregate, not by its
 * callers.</b> {@link #validateBalanced()} runs from {@link PrePersist}, so
 * an unbalanced entry cannot reach the database through any code path,
 * including one written later by someone who never read this class. Callers
 * are still expected to call it explicitly before saving, purely so the
 * failure surfaces at the point of the mistake rather than at flush time.
 */
@Entity
@Table(name = "ledger_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_transaction_reference", columnNames = "reference"),
        indexes = {
                @Index(name = "idx_ledger_transaction_payment", columnList = "payment_reference"),
                @Index(name = "idx_ledger_transaction_payout", columnList = "payout_reference")
        })
@Getter
@NoArgsConstructor
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable, caller-derived identity for this posting — in practice the
     * provider event key that caused it (see {@code ProcessedWebhookEvent}).
     * Unique, so even if the idempotency guard were bypassed the database
     * would still refuse a second posting for the same event.
     */
    @Column(name = "reference", nullable = false, updatable = false, length = 200)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 32)
    private LedgerTransactionType type;

    /** ISO-4217 code shared by every entry under this posting. */
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "narrative", nullable = false, updatable = false, length = 255)
    private String narrative;

    /** {@code Payment.reference} this posting belongs to, when it has one. */
    @Column(name = "payment_reference", updatable = false, length = 128)
    private String paymentReference;

    /** {@code Payout.reference} this posting belongs to, when it has one. */
    @Column(name = "payout_reference", updatable = false, length = 128)
    private String payoutReference;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt = Instant.now();

    @OneToMany(mappedBy = "ledgerTransaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerEntry> entries = new ArrayList<>();

    public LedgerTransaction(String reference, LedgerTransactionType type, String currency,
                            String narrative, String paymentReference, String payoutReference) {
        this.reference = reference;
        this.type = type;
        this.currency = currency;
        this.narrative = narrative;
        this.paymentReference = paymentReference;
        this.payoutReference = payoutReference;
    }

    /** @return this, so lines can be chained onto a freshly-constructed posting. */
    public LedgerTransaction debit(LedgerAccount account, long amountMinor) {
        return line(account, LedgerDirection.DEBIT, amountMinor);
    }

    public LedgerTransaction credit(LedgerAccount account, long amountMinor) {
        return line(account, LedgerDirection.CREDIT, amountMinor);
    }

    private LedgerTransaction line(LedgerAccount account, LedgerDirection direction, long amountMinor) {
        // A zero-amount line is dropped rather than rejected: posting rules
        // compute fee splits arithmetically, and a legitimately fee-free
        // charge would otherwise need every rule to branch around it.
        if (amountMinor == 0) {
            return this;
        }
        entries.add(new LedgerEntry(this, account, direction, amountMinor, currency));
        return this;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public long totalMinor(LedgerDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
    }

    /**
     * @throws UnbalancedLedgerTransactionException if debits and credits differ,
     *         or the posting has fewer than two lines (a single-sided entry
     *         can only ever be unbalanced, but the message is clearer if it
     *         says so).
     */
    @PrePersist
    public void validateBalanced() {
        if (entries.size() < 2) {
            throw new UnbalancedLedgerTransactionException(reference,
                    "a journal entry needs at least one debit and one credit, got " + entries.size() + " line(s)");
        }
        long debits = totalMinor(LedgerDirection.DEBIT);
        long credits = totalMinor(LedgerDirection.CREDIT);
        if (debits != credits) {
            throw new UnbalancedLedgerTransactionException(reference,
                    "debits (" + debits + ") do not equal credits (" + credits + ") in " + currency);
        }
    }

    /** @see LedgerEntry#rejectUpdate() */
    @PreUpdate
    void rejectUpdate() {
        throw new UnsupportedOperationException(
                "Ledger transactions are append-only; posting reference=" + reference + " cannot be modified");
    }
}
