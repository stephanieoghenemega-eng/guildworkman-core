package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.model.Payout;
import com.guildworkman.api.payment.model.PayoutStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Applies a state change, or refuses it. Every status write in this module
 * goes through here — no handler sets {@code status} directly — so the
 * transition tables on {@link PaymentStatus} and {@link PayoutStatus} are not
 * merely documentation that a future handler could forget to consult.
 *
 * <p>The check happens before any field is touched, so a refused transition
 * leaves the entity exactly as it was. That is what lets the webhook
 * processor catch the exception, record a discrepancy and commit, rather than
 * having to roll back a half-applied change.
 */
@Component
public class PaymentStateMachine {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateMachine.class);

    /** @throws IllegalPaymentTransitionException if {@code next} is not reachable from the current status. */
    public void transition(Payment payment, PaymentStatus next) {
        PaymentStatus current = payment.getStatus();
        if (!current.canTransitionTo(next)) {
            throw new IllegalPaymentTransitionException(payment.getReference(), current, next);
        }
        payment.setStatus(next);
        payment.setUpdatedAt(Instant.now());
        if (next == PaymentStatus.SUCCEEDED && payment.getCapturedAt() == null) {
            payment.setCapturedAt(Instant.now());
        }
        log.info("Payment reference={} {} -> {}", payment.getReference(), current, next);
    }

    /** @throws IllegalPaymentTransitionException if {@code next} is not reachable from the current status. */
    public void transition(Payout payout, PayoutStatus next) {
        PayoutStatus current = payout.getStatus();
        if (!current.canTransitionTo(next)) {
            throw new IllegalPaymentTransitionException(payout.getReference(), current, next);
        }
        payout.setStatus(next);
        payout.setUpdatedAt(Instant.now());
        if (next == PayoutStatus.PAID && payout.getPaidAt() == null) {
            payout.setPaidAt(Instant.now());
        }
        log.info("Payout reference={} {} -> {}", payout.getReference(), current, next);
    }
}
