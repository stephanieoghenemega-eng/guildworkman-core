package com.guildworkman.api.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One account in the platform's chart of accounts (see {@link LedgerAccounts}
 * for the fixed set and {@code LedgerAccountInitializer} for how they are
 * seeded).
 *
 * <p>Accounts are deliberately <b>currency-agnostic</b>. A balanced journal
 * entry is balanced within a single currency — {@link LedgerTransaction}
 * carries the currency and every {@link LedgerEntry} under it shares it — so
 * an account's balance is only meaningful as a per-currency figure
 * ({@code LedgerService.balanceOf(code, currency)}). The alternative,
 * seeding one account row per (code, currency) pair, would multiply the chart
 * of accounts by the number of currencies Paystack supports without making
 * any invariant stronger.
 */
@Entity
@Table(name = "ledger_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_account_code", columnNames = "code"))
@Getter
@NoArgsConstructor
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private LedgerAccountType type;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LedgerAccount(String code, LedgerAccountType type, String description) {
        this.code = code;
        this.type = type;
        this.description = description;
    }
}
