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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A recorded disagreement between the platform's books and the payment
 * provider — the output of reconciliation, and the thing an operator acts on.
 *
 * <p>Reconciliation never edits the ledger to make a divergence go away. It
 * writes one of these instead, because the two failure modes are not
 * symmetric: a discrepancy that is reported and ignored costs an operator's
 * attention, while a discrepancy that is silently "corrected" destroys the
 * evidence that anything was ever wrong. See docs/PAYMENTS_LEDGER.md,
 * "Reconciliation".
 *
 * <p>{@code dedupeKey} keeps a sweep that runs every few minutes from
 * producing the same finding over and over: it combines the type with the
 * reference and the observed values, so a genuinely new divergence on the
 * same payment still opens a new row while an unchanged one does not.
 */
@Entity
@Table(name = "payment_reconciliation_discrepancies",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_discrepancy_dedupe", columnNames = "dedupe_key"),
        indexes = {
                @Index(name = "idx_payment_discrepancy_status", columnList = "status"),
                @Index(name = "idx_payment_discrepancy_reference", columnList = "resource_reference")
        })
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationDiscrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, updatable = false, length = 300)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 32)
    private DiscrepancyType type;

    /** The {@code Payment}/{@code Payout} reference this finding is about. */
    @Column(name = "resource_reference", nullable = false, updatable = false, length = 128)
    private String resourceReference;

    /** What the platform's books say — a status name, an amount, or a short phrase. */
    @Column(name = "platform_state", updatable = false, length = 200)
    private String platformState;

    /** What the provider says. */
    @Column(name = "provider_state", updatable = false, length = 200)
    private String providerState;

    /** Human-readable summary, written to be readable in an alert without extra lookup. */
    @Column(name = "detail", nullable = false, updatable = false, length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DiscrepancyStatus status = DiscrepancyStatus.OPEN;

    /** Free-text note left by whoever acknowledged or resolved it. */
    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ReconciliationDiscrepancy(DiscrepancyType type, String resourceReference,
                                     String platformState, String providerState, String detail) {
        this.type = type;
        this.resourceReference = resourceReference;
        this.platformState = platformState;
        this.providerState = providerState;
        this.detail = detail;
        this.dedupeKey = dedupeKey(type, resourceReference, platformState, providerState);
    }

    /**
     * Truncated to the column width so an unusually long provider state can't
     * fail the insert — the leading type/reference segment is the part that
     * makes it discriminating, and it always survives.
     */
    public static String dedupeKey(DiscrepancyType type, String resourceReference,
                                   String platformState, String providerState) {
        String key = type + "|" + resourceReference + "|" + platformState + "|" + providerState;
        return key.length() <= 300 ? key : key.substring(0, 300);
    }
}
