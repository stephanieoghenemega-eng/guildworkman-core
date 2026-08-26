package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.WebhookOutcome;

/**
 * The webhook response body. Always accompanies a 200 — see
 * {@link WebhookOutcome} for why even a refused event is acknowledged — and
 * says which case applied, so a Paystack dashboard delivery log shows what
 * happened rather than an opaque {@code OK}.
 */
public record WebhookAcknowledgement(String outcome) {

    public static WebhookAcknowledgement of(WebhookOutcome outcome) {
        return new WebhookAcknowledgement(outcome.name());
    }
}
