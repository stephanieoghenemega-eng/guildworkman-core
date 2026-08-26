package com.guildworkman.api.payment.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Binds {@code payments.*}: fee policy and reconciliation tuning. */
@Component
@ConfigurationProperties(prefix = "payments")
@Getter
@Setter
public class PaymentProperties {

    /**
     * The platform's commission, in basis points of the captured amount
     * (250 = 2.5%). Kept in basis points rather than a percentage double so
     * the fee is computed with integer arithmetic end to end — a
     * {@code double} percentage is how a ledger acquires a one-kobo rounding
     * drift that nothing can account for later.
     */
    private int platformFeeBps = 250;

    /** ISO-4217 code used when a caller does not name one. */
    private String defaultCurrency = "NGN";

    /**
     * How long a payment is left alone before reconciliation will ask the
     * provider about it. This is the client's window to actually finish
     * checkout — sweeping a two-second-old {@code INITIATED} payment would
     * report "the provider has never heard of this" for every payment ever
     * created.
     */
    private Duration reconciliationGrace = Duration.ofMinutes(15);

    /** How many payments one reconciliation sweep examines. */
    private int reconciliationBatchSize = 50;
}
