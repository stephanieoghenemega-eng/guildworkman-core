package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.DiscrepancyStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Moves a finding along the operator workflow.
 *
 * <p>There is no field here for changing the books. Closing a finding records
 * that a human dealt with it; if dealing with it required money to move, that
 * happens as a new journal entry, not as an edit to this row.
 *
 * @param status         {@code ACKNOWLEDGED} or {@code RESOLVED}
 * @param resolutionNote what was done, or why nothing needed to be
 */
public record ResolveDiscrepancyRequest(
        @NotNull DiscrepancyStatus status,
        @Size(max = 500) String resolutionNote) {
}
