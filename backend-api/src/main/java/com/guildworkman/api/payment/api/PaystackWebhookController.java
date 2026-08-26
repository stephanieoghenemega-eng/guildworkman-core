package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.WebhookOutcome;
import com.guildworkman.api.payment.service.PaystackSignatureVerifier;
import com.guildworkman.api.payment.service.PaystackWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Paystack webhooks. The only unauthenticated endpoint that can move
 * money, and the reasons that is safe are worth stating plainly:
 *
 * <ol>
 *   <li><b>It authenticates the payload, not the caller.</b> Paystack posts
 *       from a rotating set of IPs with no credential of ours; the HMAC over
 *       the body <em>is</em> the authentication, and it is checked before
 *       anything else happens. An attacker who cannot compute the MAC cannot
 *       do anything here but receive a 401.</li>
 *   <li><b>It lives outside {@code /api/v1/payments}.</b> Every other payment
 *       route requires a bearer token. If the public matcher lived under that
 *       prefix, a future edit that widened it by one wildcard would silently
 *       expose the authenticated routes too. A separate path means the
 *       permit-all rule cannot reach them by accident. See
 *       {@code SecurityConfig}.</li>
 * </ol>
 *
 * <p><b>The body is taken as {@code byte[]}, and that is not incidental.</b>
 * The signature covers the exact bytes Paystack sent. Binding to a DTO would
 * hand the verifier a re-serialized approximation whose key order and number
 * formatting Jackson is free to change, and every such change breaks the MAC.
 * Taking the raw bytes is also what lets verification run <em>before</em> the
 * payload reaches a parser at all.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class PaystackWebhookController {

    private final PaystackWebhookService webhookService;

    @PostMapping(value = "/paystack", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive a Paystack webhook",
            description = "Unauthenticated by design and protected by an HMAC-SHA512 signature over the raw "
                    + "request body. Idempotent: a redelivered event produces exactly one ledger effect. "
                    + "Always answers 200 once the signature verifies -- including for events that are "
                    + "refused -- because a provider retry cannot make a refused event legal, and the "
                    + "divergence is recorded as a reconciliation discrepancy instead.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Event accepted: APPLIED, DUPLICATE, IGNORED or REJECTED"),
            @ApiResponse(responseCode = "400",
                    description = "Signature verified but the body is not a readable Paystack envelope"),
            @ApiResponse(responseCode = "401",
                    description = "Missing or invalid x-paystack-signature; no state was touched")
    })
    public WebhookAcknowledgement receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = PaystackSignatureVerifier.SIGNATURE_HEADER, required = false) String signature) {
        WebhookOutcome outcome = webhookService.handle(rawBody, signature);
        return WebhookAcknowledgement.of(outcome);
    }
}
