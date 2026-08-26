package com.guildworkman.api.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One debit or credit line of a {@link LedgerTransaction}. Immutable once
 * written — see the append-only discussion on {@link LedgerTransaction}.
 *
 * <p>{@code amountMinor} is a non-negative magnitude in the currency's
 * <em>minor units</em> (kobo for NGN, cents for USD), matching the integer
 * amounts Paystack sends and receives. Money never becomes a {@code double}
 * or a scaled {@code BigDecimal} anywhere on the posting path, so a balanced
 * entry stays balanced to the last unit; conversion to a human-readable
 * major-unit figure happens only at the API boundary, using the currency's
 * own fraction digits (see {@code MinorUnits}).
 */
@Entity
@Table(name = "ledger_entries",
        indexes = {
                @Index(name = "idx_ledger_entry_transaction", columnList = "ledger_transaction_id"),
                @Index(name = "idx_ledger_entry_account", columnList = "account_id,currency"),
                // The trial balance sums every entry filtered by currency and
                // direction; without this it is a sequential scan of the whole
                // journal on each sweep. Leading with currency matches both
                // that query and the per-account one above.
                @Index(name = "idx_ledger_entry_currency_direction", columnList = "currency,direction")
        })
@Getter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_transaction_id", nullable = false, updatable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ledger_entry_transaction"))
    private LedgerTransaction ledgerTransaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ledger_entry_account"))
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 6)
    private LedgerDirection direction;

    /** Non-negative magnitude in minor units; {@link #direction} carries the sign. */
    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    LedgerEntry(LedgerTransaction ledgerTransaction, LedgerAccount account,
                LedgerDirection direction, long amountMinor, String currency) {
        if (amountMinor < 0) {
            // A negative debit is an unsigned credit in disguise: allowing it
            // would let a caller post "balanced" pairs that move money in a
            // direction the entry doesn't claim.
            throw new IllegalArgumentException("Ledger entry amount must be non-negative, got " + amountMinor);
        }
        this.ledgerTransaction = ledgerTransaction;
        this.account = account;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    /**
     * Append-only guard. Every column above is already {@code updatable = false},
     * so Hibernate would not emit an {@code UPDATE} for a mutated field — it
     * would silently drop the change, which is worse than refusing it. This
     * hook makes any attempt loud instead. See {@link LedgerTransaction} for
     * why deletes are handled at the database-privilege level rather than here.
     */
    @PreUpdate
    void rejectUpdate() {
        throw new UnsupportedOperationException(
                "Ledger entries are append-only; entry id=" + id + " cannot be modified");
    }
}
