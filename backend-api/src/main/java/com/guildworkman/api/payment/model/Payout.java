package com.guildworkman.api.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An outbound transfer of collected funds to a skilled worker — the other
 * side of {@link LedgerAccounts#WORKER_PAYABLE}.
 *
 * <p>Like {@link Payment}, this is derived state over the append-only ledger;
 * see that class for why the derived rows are mutable and the journal is not.
 *
 * <p>Rows are created lazily by the first {@code transfer.*} webhook that
 * names a transfer reference (in {@link PayoutStatus#PENDING}), because this
 * PR does not call Paystack's Transfer API — doing so requires transfer
 * recipient management, which is its own piece of work. The consequence
 * worth knowing: a transfer initiated out-of-band appears in the books the
 * moment the provider reports on it, and the worker it belongs to is taken
 * from the transfer's own metadata. A transfer whose metadata names no
 * worker this platform knows is recorded with a null
 * {@code skilledWorkerId} and flagged as a discrepancy rather than dropped.
 */
@Entity
@Table(name = "payouts",
        uniqueConstraints = @UniqueConstraint(name = "uk_payout_reference", columnNames = "reference"),
        indexes = {
                @Index(name = "idx_payout_status", columnList = "status"),
                @Index(name = "idx_payout_worker", columnList = "skilled_worker_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Paystack's transfer reference (or transfer code, if no reference was set). */
    @Column(name = "reference", nullable = false, updatable = false, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private PaymentProvider provider = PaymentProvider.PAYSTACK;

    @Column(name = "provider_transfer_id")
    private Long providerTransferId;

    /** Null when the provider's transfer metadata names no known worker; see class Javadoc. */
    @Column(name = "skilled_worker_id")
    private Long skilledWorkerId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    /** Paystack's transfer fee, when reported. */
    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PayoutStatus status = PayoutStatus.PENDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Version
    private long version;

    public Payout(String reference, Long skilledWorkerId, long amountMinor, String currency) {
        this.reference = reference;
        this.skilledWorkerId = skilledWorkerId;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }
}
