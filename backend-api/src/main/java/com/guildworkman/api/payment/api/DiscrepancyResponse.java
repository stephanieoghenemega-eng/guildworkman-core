package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.ReconciliationDiscrepancy;

import java.time.Instant;

/** An open (or closed) reconciliation finding, as an operator sees it. */
public record DiscrepancyResponse(
        Long id,
        String type,
        String resourceReference,
        String platformState,
        String providerState,
        String detail,
        String status,
        String resolutionNote,
        Instant detectedAt,
        Instant updatedAt) {

    public static DiscrepancyResponse from(ReconciliationDiscrepancy discrepancy) {
        return new DiscrepancyResponse(
                discrepancy.getId(),
                discrepancy.getType().name(),
                discrepancy.getResourceReference(),
                discrepancy.getPlatformState(),
                discrepancy.getProviderState(),
                discrepancy.getDetail(),
                discrepancy.getStatus().name(),
                discrepancy.getResolutionNote(),
                discrepancy.getDetectedAt(),
                discrepancy.getUpdatedAt());
    }
}
