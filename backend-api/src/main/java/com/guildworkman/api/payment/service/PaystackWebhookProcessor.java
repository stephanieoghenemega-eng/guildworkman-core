package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.DiscrepancyType;
import com.guildworkman.api.payment.model.ReconciliationDiscrepancy;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.model.Payout;
import com.guildworkman.api.payment.model.PayoutStatus;
import com.guildworkman.api.payment.model.ProcessedWebhookEvent;
import com.guildworkman.api.payment.model.WebhookOutcome;
import com.guildworkman.api.payment.repository.PaymentRepository;
import com.guildworkman.api.payment.repository.PayoutRepository;
import com.guildworkman.api.payment.repository.ProcessedWebhookEventRepository;
import com.guildworkman.api.services.ServiceUtils.TransactionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Applies one verified Paystack event to the platform's state, exactly once.
 *
 * <h2>The transaction boundary is the correctness argument</h2>
 * Claiming the event (inserting {@link ProcessedWebhookEvent}) and applying
 * its effect happen in the <em>same</em> transaction. That gives both
 * halves of the idempotency guarantee:
 * <ul>
 *   <li>a redelivery after success finds the row and does nothing, so the
 *       ledger effect happens once;</li>
 *   <li>a redelivery after a failure finds nothing — the claim rolled back
 *       with the work — so a transient database error doesn't silently
 *       swallow a payment notification.</li>
 * </ul>
 * Two deliveries racing on two instances both try to insert the same
 * {@code event_key}; the unique index picks a winner and the loser's
 * {@code DataIntegrityViolationException} rolls its whole transaction back,
 * having applied nothing. {@link PaystackWebhookService} catches it there and
 * answers {@link WebhookOutcome#DUPLICATE}. This is why the exception must
 * escape this method rather than be handled inside it: once a transaction is
 * marked rollback-only there is nothing useful left to do in it.
 *
 * <h2>Rejected is not failed</h2>
 * An event that cannot legally be applied — an out-of-order refund, a
 * reference this platform has never issued — is recorded, flagged as a
 * discrepancy, and answered 200. Refusing it with a 4xx would make Paystack
 * retry an event that will never become legal, and eventually disable the
 * endpoint; the divergence belongs in the discrepancy table where somebody
 * will see it, not in a redelivery queue.
 */
@Service
@RequiredArgsConstructor
public class PaystackWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookProcessor.class);

    private final ProcessedWebhookEventRepository processedEvents;
    private final PaymentRepository payments;
    private final PayoutRepository payouts;
    private final LedgerService ledger;
    private final PaymentStateMachine stateMachine;
    private final DiscrepancyRecorder discrepancies;
    private final TransactionService transactionService;

    /** @param outcome what happened; @param detail why, for the audit row and the response */
    private record Result(WebhookOutcome outcome, String detail) {

        static Result applied() {
            return new Result(WebhookOutcome.APPLIED, null);
        }

        static Result applied(String detail) {
            return new Result(WebhookOutcome.APPLIED, detail);
        }

        static Result ignored(String detail) {
            return new Result(WebhookOutcome.IGNORED, detail);
        }

        static Result rejected(String detail) {
            return new Result(WebhookOutcome.REJECTED, detail);
        }
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if another
     *         delivery of the same event committed first; the caller reads that
     *         as a duplicate.
     */
    @Transactional
    public WebhookOutcome process(PaystackEvent event) {
        Optional<ProcessedWebhookEvent> alreadySeen = processedEvents.findByEventKey(event.eventKey());
        if (alreadySeen.isPresent()) {
            log.info("Ignoring duplicate Paystack event key={} (first seen at {}, outcome {})",
                    event.eventKey(), alreadySeen.get().getReceivedAt(), alreadySeen.get().getOutcome());
            return WebhookOutcome.DUPLICATE;
        }

        ProcessedWebhookEvent record =
                new ProcessedWebhookEvent(event.eventKey(), event.type(), event.reference());
        // Flushed immediately so a concurrent delivery collides here — before
        // any ledger work — rather than at commit time.
        processedEvents.saveAndFlush(record);

        Result result = dispatch(event);
        record.setOutcome(result.outcome());
        record.setDetail(truncate(result.detail()));
        log.info("Paystack event key={} type={} reference={} outcome={}",
                event.eventKey(), event.type(), event.reference(), result.outcome());
        return result.outcome();
    }

    private Result dispatch(PaystackEvent event) {
        try {
            switch (event.type()) {
                case PaystackEventTypes.CHARGE_SUCCESS:
                    return handleChargeSuccess(event);
                case PaystackEventTypes.CHARGE_FAILED:
                    return handleChargeFailed(event);
                case PaystackEventTypes.REFUND_PROCESSED:
                    return handleRefundProcessed(event);
                case PaystackEventTypes.REFUND_FAILED:
                    return Result.ignored("Refund failed at the provider; no money moved, so nothing to post");
                case PaystackEventTypes.TRANSFER_SUCCESS:
                    return handleTransferSuccess(event);
                case PaystackEventTypes.TRANSFER_FAILED:
                    return handleTransferOutcome(event, PayoutStatus.FAILED);
                case PaystackEventTypes.TRANSFER_REVERSED:
                    return handleTransferReversed(event);
                default:
                    return Result.ignored("No handler for event type " + event.type());
            }
        } catch (IllegalPaymentTransitionException ex) {
            // The state machine refused before touching the entity, so nothing
            // is half-applied and this transaction can still commit the audit
            // row that says so.
            Long discrepancyId = discrepancies.record(DiscrepancyType.ILLEGAL_TRANSITION,
                            ex.getResourceReference(), ex.getFrom(), event.type(), ex.getMessage())
                    .map(ReconciliationDiscrepancy::getId).orElse(null);
            // An out-of-order or replayed-after-refund event is the case an
            // operator is most likely to have to explain to someone, so the
            // refusal says which event, which reference, which transition, and
            // which finding to open — rather than leaving them to join the
            // processed-event row to the discrepancy table by timestamp.
            log.warn("Refused Paystack event key={} type={} reference={}: illegal transition from {} "
                            + "(discrepancy id={})",
                    event.eventKey(), event.type(), ex.getResourceReference(), ex.getFrom(), discrepancyId);
            return Result.rejected(ex.getMessage());
        }
    }

    // --- charges -----------------------------------------------------------

    private Result handleChargeSuccess(PaystackEvent event) {
        Payment payment = payments.findByReference(event.transactionReference()).orElse(null);
        if (payment == null) {
            return unknownReference(event);
        }
        if (!payment.getCurrency().equalsIgnoreCase(event.currency())) {
            // Posting a NGN charge into a USD payment's books would balance
            // arithmetically and mean nothing. Refuse and report.
            discrepancies.record(DiscrepancyType.AMOUNT_DIVERGENCE, payment.getReference(),
                    payment.getCurrency(), event.currency(),
                    "Provider reported a different currency than the payment was created in");
            return Result.rejected("Currency mismatch: platform " + payment.getCurrency()
                    + ", provider " + event.currency());
        }

        long gross = event.amountMinor();
        long providerFee = event.feesMinor();
        if (gross <= 0 || providerFee < 0 || providerFee > gross) {
            // A negative or oversized provider fee would produce a negative
            // ledger line, which LedgerEntry refuses outright; catching it here
            // gives an operator a finding instead of a stack trace.
            discrepancies.record(DiscrepancyType.AMOUNT_DIVERGENCE, payment.getReference(),
                    String.valueOf(payment.getAmountMinor()), gross + " (fees " + providerFee + ")",
                    "Provider reported an amount/fee combination that cannot be posted");
            return Result.rejected("Unpostable amount " + gross + " with fees " + providerFee);
        }

        // Ordered so nothing is mutated before the transition is accepted.
        stateMachine.transition(payment, PaymentStatus.SUCCEEDED);

        long platformFee = ledger.platformFeeOn(gross);
        payment.setProviderTransactionId(event.providerId());
        payment.setCapturedAmountMinor(gross);
        payment.setProviderFeeMinor(providerFee);
        payment.setPlatformFeeMinor(platformFee);
        payments.save(payment);

        ledger.postChargeCapture(payment, event.eventKey(), gross, providerFee, platformFee);
        transactionService.projectFromPayment(payment);

        if (gross != payment.getAmountMinor()) {
            // The money moved, so it is booked at the amount that actually
            // moved — declining to record a real capture because it disagreed
            // with our expectation would put the books further from reality,
            // not closer. The disagreement is reported separately.
            discrepancies.record(DiscrepancyType.AMOUNT_DIVERGENCE, payment.getReference(),
                    String.valueOf(payment.getAmountMinor()), String.valueOf(gross),
                    "Captured amount differs from the amount the payment was initialized for");
            return Result.applied("Captured " + gross + ", expected " + payment.getAmountMinor());
        }
        return Result.applied();
    }

    private Result handleChargeFailed(PaystackEvent event) {
        Payment payment = payments.findByReference(event.transactionReference()).orElse(null);
        if (payment == null) {
            return unknownReference(event);
        }
        stateMachine.transition(payment, PaymentStatus.FAILED);
        payment.setFailureReason(truncate(event.failureReason()));
        payments.save(payment);
        transactionService.projectFromPayment(payment);
        // No ledger posting: a declined charge moved no money, and an entry
        // for it would have to be balanced against something that never existed.
        return Result.applied("Charge failed at the provider; no ledger effect");
    }

    // --- refunds -----------------------------------------------------------

    private Result handleRefundProcessed(PaystackEvent event) {
        Payment payment = payments.findByReference(event.transactionReference()).orElse(null);
        if (payment == null) {
            return unknownReference(event);
        }

        long refund = event.amountMinor();
        long alreadyRefunded = payment.getRefundedAmountMinor();
        long captured = payment.getCapturedAmountMinor();

        // Guarded on isCaptured() deliberately. For a payment that was never
        // captured, "the refund exceeds the captured amount" is technically
        // true and tells an operator nothing — every field in it is zero. The
        // informative answer is the state machine's, so this check is skipped
        // and the transition below refuses the event as out-of-order instead.
        if (payment.getStatus().isCaptured() && (refund <= 0 || alreadyRefunded + refund > captured)) {
            discrepancies.record(DiscrepancyType.AMOUNT_DIVERGENCE, payment.getReference(),
                    "captured " + captured + ", refunded " + alreadyRefunded, String.valueOf(refund),
                    "Refund would exceed the captured amount");
            return Result.rejected("Refund of " + refund + " exceeds the " + (captured - alreadyRefunded)
                    + " still refundable");
        }

        long refundedAfter = alreadyRefunded + refund;
        // captured > 0 keeps a not-yet-captured payment out of the REFUNDED
        // branch, where 0 == 0 would otherwise look like "fully refunded".
        PaymentStatus next = (captured > 0 && refundedAfter == captured)
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        stateMachine.transition(payment, next);

        payment.setRefundedAmountMinor(refundedAfter);
        payments.save(payment);

        // alreadyRefunded is passed explicitly rather than re-read: the payment
        // has just been updated, and the commission share depends on what had
        // been refunded before this event.
        ledger.postRefund(payment, event.eventKey(), refund, alreadyRefunded);
        transactionService.projectFromPayment(payment);
        return Result.applied("Refunded " + refund + " of " + captured);
    }

    // --- payouts -----------------------------------------------------------

    private Result handleTransferSuccess(PaystackEvent event) {
        Payout payout = findOrCreatePayout(event);
        stateMachine.transition(payout, PayoutStatus.PAID);
        payout.setProviderTransferId(event.providerId());
        payout.setFeeMinor(event.feesMinor());
        payouts.save(payout);

        ledger.postPayout(payout, event.eventKey());

        if (payout.getSkilledWorkerId() == null) {
            // The transfer happened and the books must say so; who it was for
            // is the open question, and it is asked in the discrepancy table.
            discrepancies.record(DiscrepancyType.UNKNOWN_REFERENCE, payout.getReference(),
                    "no linked skilled worker", event.type(),
                    "Provider transfer settled but its metadata names no skilled worker this platform knows");
            return Result.applied("Payout settled with no linked skilled worker");
        }
        return Result.applied();
    }

    private Result handleTransferOutcome(PaystackEvent event, PayoutStatus next) {
        Payout payout = findOrCreatePayout(event);
        stateMachine.transition(payout, next);
        payout.setProviderTransferId(event.providerId());
        payout.setFailureReason(truncate(event.failureReason()));
        payouts.save(payout);
        // A transfer that never left posts nothing: the payout entry is only
        // written on transfer.success, so there is nothing here to undo.
        return Result.applied("Transfer " + next.name().toLowerCase(java.util.Locale.ROOT)
                + "; no ledger effect");
    }

    private Result handleTransferReversed(PaystackEvent event) {
        Payout payout = findOrCreatePayout(event);
        // Only legal from PAID — a reversal of a payout the books never posted
        // is the out-of-order case, and PayoutStatus refuses it here.
        stateMachine.transition(payout, PayoutStatus.REVERSED);
        payout.setFailureReason(truncate(event.failureReason()));
        payouts.save(payout);

        ledger.postPayoutReversal(payout, event.eventKey());
        return Result.applied("Payout reversed; compensating entry posted");
    }

    /**
     * Payouts are not initiated by this service yet (see {@link Payout}), so
     * the first {@code transfer.*} event for a reference is also what creates
     * the row. It is created {@code PENDING} — the state the payout was in
     * immediately before the event being processed — so the event still has to
     * pass the state machine rather than bypassing it by defining the row into
     * its own outcome.
     */
    private Payout findOrCreatePayout(PaystackEvent event) {
        return payouts.findByReference(event.reference()).orElseGet(() -> {
            Payout payout = new Payout(event.reference(), event.metadataLong("skilledWorkerId"),
                    event.amountMinor(), event.currency());
            payout.setCreatedAt(Instant.now());
            log.info("Recording previously-unseen payout reference={} from event {}",
                    event.reference(), event.type());
            return payouts.saveAndFlush(payout);
        });
    }

    // --- shared ------------------------------------------------------------

    private Result unknownReference(PaystackEvent event) {
        discrepancies.record(DiscrepancyType.UNKNOWN_REFERENCE, event.transactionReference(),
                "no such payment", event.type(),
                "Provider reported on a payment reference this platform has no record of");
        return Result.rejected("No payment for reference " + event.transactionReference());
    }

    /** Keeps free-text provider strings inside the columns that store them. */
    private static String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 497) + "...";
    }
}
