package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.service.LedgerService;
import com.guildworkman.api.payment.service.PaymentInitiationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The client-facing payment surface. See {@code docs/PAYMENTS_LEDGER.md} for
 * the full write-up.
 *
 * <p>The behaviour worth knowing about this controller is what it does
 * <em>not</em> do: there is no "confirm my payment" call. Initialization
 * hands back a checkout URL and the story ends there as far as the client is
 * concerned — capture is driven entirely by the signed webhook, so a client
 * that closes the tab, loses signal, or never returns from the redirect still
 * has its payment completed and its ledger entries posted. {@code GET
 * /{reference}} exists to <em>read</em> that state, never to cause it. That
 * is the difference between this and the redirect-callback flow it replaces,
 * where a dropped callback lost the payment.
 *
 * <p>Webhooks arrive on a separate, unauthenticated path — see
 * {@code PaystackWebhookController}.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentInitiationService initiationService;
    private final LedgerService ledgerService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a payment and get a Paystack checkout URL",
            description = "Creates the payment locally under a platform-generated reference, then asks "
                    + "Paystack for a checkout URL. The client does not need to return to the platform "
                    + "afterwards: the payment is completed by the signed webhook.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payment created; use authorizationUrl to pay"),
            @ApiResponse(responseCode = "400", description = "Invalid amount, currency or email"),
            @ApiResponse(responseCode = "502", description = "Paystack could not be reached or refused the transaction")
    })
    public InitializePaymentResponse initialize(@Valid @RequestBody InitializePaymentRequest request) {
        Payment payment = initiationService.initiate(
                request.clientId(),
                request.skilledWorkerId(),
                request.appointmentId(),
                request.customerEmail(),
                request.amount(),
                request.currency());
        return InitializePaymentResponse.from(payment);
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Read a payment's current state",
            description = "Read-only. Polling this is a convenience for a client that did come back from "
                    + "the redirect; it is not what completes the payment, and not calling it changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payment's current state"),
            @ApiResponse(responseCode = "404", description = "No payment with that reference")
    })
    public PaymentStatusResponse get(@PathVariable String reference) {
        return PaymentStatusResponse.from(initiationService.get(reference));
    }

    @GetMapping("/{reference}/ledger")
    @Operation(summary = "List the journal entries a payment produced",
            description = "The audit trail: every balanced posting made for this payment, oldest first. "
                    + "A refund appears as an additional posting, never as an edit to the capture.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Journal entries, oldest first"),
            @ApiResponse(responseCode = "404", description = "No payment with that reference")
    })
    public List<LedgerPostingResponse> ledger(@PathVariable String reference) {
        // Resolve the payment first so an unknown reference is a 404 rather
        // than an empty list that reads as "this payment did nothing".
        Payment payment = initiationService.get(reference);
        return ledgerService.postingsForPayment(payment.getReference()).stream()
                .map(LedgerPostingResponse::from)
                .toList();
    }
}
