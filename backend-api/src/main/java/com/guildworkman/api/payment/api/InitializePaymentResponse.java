package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.MinorUnits;
import com.guildworkman.api.payment.model.Payment;

import java.math.BigDecimal;

/**
 * Everything the client needs to complete a payment — and the reference it
 * should quote afterwards.
 *
 * <p>The client is not required to come back. Completion is driven by the
 * signed webhook, so a browser that never returns from the redirect costs
 * nothing; this response is a convenience, not a step in the protocol.
 *
 * @param reference        platform-generated payment reference
 * @param authorizationUrl Paystack-hosted checkout page to send the client to
 * @param accessCode       code for Paystack's inline checkout, if the client embeds it instead
 * @param amount           the amount in major units, echoed back
 */
public record InitializePaymentResponse(String reference, String authorizationUrl, String accessCode,
                                        BigDecimal amount, String currency, String status) {

    public static InitializePaymentResponse from(Payment payment) {
        return new InitializePaymentResponse(
                payment.getReference(),
                payment.getAuthorizationUrl(),
                payment.getAccessCode(),
                MinorUnits.toMajor(payment.getAmountMinor(), payment.getCurrency()),
                payment.getCurrency(),
                payment.getStatus().name());
    }
}
