package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.DiscrepancyStatus;
import com.guildworkman.api.payment.service.LedgerService;
import com.guildworkman.api.payment.service.PaymentReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The operator's view of reconciliation: what disagrees with the provider,
 * and whether the books balance.
 *
 * <p>ADMIN-only throughout. These endpoints expose platform-wide financial
 * position — a trial balance is every naira the platform holds — and the
 * discrepancy list is, by construction, a list of the places money might be
 * wrong.
 *
 * <p>Note the shape of the write endpoint: an operator can say a finding has
 * been dealt with, and nothing else. There is no endpoint here that edits a
 * payment, a journal entry, or the observed values on a finding. Correcting
 * the books means posting a new entry, which is a code path with its own
 * rules — not an operator's text field.
 */
@RestController
@RequestMapping("/api/v1/payments/reconciliation")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentReconciliationController {

    private final PaymentReconciliationService reconciliationService;
    private final LedgerService ledgerService;

    @GetMapping("/discrepancies")
    @Operation(summary = "List reconciliation findings",
            description = "ADMIN only. Most recently detected first. Defaults to OPEN findings -- the ones "
                    + "nobody has looked at yet.")
    public List<DiscrepancyResponse> discrepancies(
            @RequestParam(defaultValue = "OPEN") DiscrepancyStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return reconciliationService.listDiscrepancies(status, limit).stream()
                .map(DiscrepancyResponse::from)
                .toList();
    }

    @GetMapping("/discrepancies/by-reference/{reference}")
    @Operation(summary = "List every finding recorded against one payment or payout reference",
            description = "ADMIN only. The history of what has disagreed about a single reference, "
                    + "including findings already resolved.")
    public List<DiscrepancyResponse> discrepanciesFor(@PathVariable String reference) {
        return reconciliationService.discrepanciesFor(reference).stream()
                .map(DiscrepancyResponse::from)
                .toList();
    }

    @PostMapping("/discrepancies/{id}")
    @Operation(summary = "Acknowledge or resolve a finding",
            description = "ADMIN only. Records that a human has dealt with the finding. This does not "
                    + "change the ledger, the payment, or what the finding observed -- if money has to "
                    + "move, that is a new journal entry.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated finding"),
            @ApiResponse(responseCode = "404", description = "No finding with that id")
    })
    public DiscrepancyResponse updateDiscrepancy(@PathVariable Long id,
                                                 @Valid @RequestBody ResolveDiscrepancyRequest request) {
        return DiscrepancyResponse.from(
                reconciliationService.updateDiscrepancy(id, request.status(), request.resolutionNote()));
    }

    @GetMapping("/trial-balance")
    @Operation(summary = "Total debits against total credits, plus each account's balance",
            description = "ADMIN only. `balanced` is the invariant the ledger exists to keep; if it is "
                    + "ever false, a posting rule is wrong and every figure derived from the ledger is "
                    + "suspect. The scheduled sweep checks the same thing and records a LEDGER_IMBALANCE "
                    + "finding, so nobody has to remember to call this.")
    public TrialBalanceResponse trialBalance(@RequestParam(defaultValue = "NGN") String currency) {
        String normalized = currency.toUpperCase(java.util.Locale.ROOT);
        return TrialBalanceResponse.from(
                ledgerService.trialBalance(normalized),
                ledgerService.accountBalances(normalized));
    }

    @PostMapping("/run")
    @Operation(summary = "Run a reconciliation sweep now",
            description = "ADMIN only. Same work the scheduler does, on demand -- for confirming a fix "
                    + "without waiting for the next tick. Returns how many payments the provider actually "
                    + "answered about.")
    public ReconciliationRunResponse run() {
        int examined = reconciliationService.reconcilePayments();
        reconciliationService.assertLedgerBalances();
        return new ReconciliationRunResponse(examined);
    }

    /** @param paymentsExamined payments the provider answered about; unreachable ones are not counted */
    public record ReconciliationRunResponse(int paymentsExamined) {
    }
}
