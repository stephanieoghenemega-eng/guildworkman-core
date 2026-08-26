package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.DiscrepancyStatus;
import com.guildworkman.api.payment.model.DiscrepancyType;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.model.ReconciliationDiscrepancy;
import com.guildworkman.api.payment.repository.PaymentRepository;
import com.guildworkman.api.payment.repository.ReconciliationDiscrepancyRepository;
import com.guildworkman.api.services.ServiceUtils.TransactionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Proves the platform's books agree with Paystack's — and says so out loud
 * when they don't.
 *
 * <h2>What it will and won't do</h2>
 * It never posts to the ledger, and it never changes the state of a payment
 * whose money has already been captured. Both restrictions come from the same
 * observation: an automatic fix destroys the evidence that anything was ever
 * wrong, and an accounting system whose divergences quietly disappear is
 * worse than one with no reconciliation at all, because it looks correct.
 *
 * <p>The one state change it does make is deliberately not a correction. When
 * the provider reports a charge as {@code failed} or {@code abandoned} and
 * the platform still has it open, no money exists on either side; the sweep
 * is simply learning the outcome that the webhook, for whatever reason, did
 * not deliver. Applying it closes out a dead intent, has no ledger effect,
 * and — importantly — is not recorded as a discrepancy, because a client who
 * closed the checkout tab is not an accounting problem. Every case where
 * money is or might be involved is reported and left alone:
 *
 * <table border="1">
 *   <caption>Reconciliation outcomes</caption>
 *   <tr><th>Provider says</th><th>Platform says</th><th>Action</th></tr>
 *   <tr><td>success</td><td>not captured</td><td>{@code PROVIDER_STATUS_DIVERGENCE} — money we don't know about</td></tr>
 *   <tr><td>failed/abandoned</td><td>captured</td><td>{@code PROVIDER_STATUS_DIVERGENCE} — money we think we have</td></tr>
 *   <tr><td>failed/abandoned</td><td>not captured</td><td>apply the outcome; no finding, no ledger effect</td></tr>
 *   <tr><td>success</td><td>captured, different amount</td><td>{@code AMOUNT_DIVERGENCE}</td></tr>
 *   <tr><td>nothing (404)</td><td>anything</td><td>{@code MISSING_PROVIDER_RECORD}</td></tr>
 *   <tr><td>unreachable</td><td>anything</td><td>WARN, retry next sweep — an outage is not a divergence</td></tr>
 * </table>
 *
 * <h2>Not transactional</h2>
 * The sweep holds no transaction across its Paystack calls; each repository
 * write is its own. One unreachable payment therefore costs that payment's
 * turn, not the batch, and a slow provider can never pin a database
 * connection for the length of an HTTP timeout.
 */
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    /**
     * States worth asking the provider about. Terminal money-less states
     * ({@code FAILED}, {@code ABANDONED}) are settled and cost nothing to
     * leave alone; {@code REFUNDED}/{@code REVERSED} are terminal too. A
     * captured payment is included exactly once — {@code reconciledAt} is
     * stamped afterwards and the query below skips anything already stamped —
     * so the sweep confirms each capture against the provider without
     * re-verifying the entire history of the platform every minute.
     */
    private static final EnumSet<PaymentStatus> RECONCILABLE =
            EnumSet.of(PaymentStatus.INITIATED, PaymentStatus.PENDING, PaymentStatus.SUCCEEDED);

    private final PaymentRepository payments;
    private final PaystackClient paystackClient;
    private final DiscrepancyRecorder discrepancies;
    private final LedgerService ledger;
    private final PaymentProperties properties;
    private final TransactionService transactionService;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final PaymentMetrics metrics;

    @Scheduled(fixedDelayString = "${payments.reconciliation.poll-delay-ms:60000}")
    public void reconcile() {
        // A @Scheduled method that throws is logged once by Spring's error
        // handler and is otherwise invisible — the sweep simply stops
        // happening, which looks exactly like "no divergence found". Counting
        // the failure is what makes a stalled reconciler alertable, and the
        // throw is preserved so the scheduler's own logging still fires.
        try {
            reconcilePayments();
            assertLedgerBalances();
            metrics.sweepCompleted(false);
        } catch (RuntimeException ex) {
            metrics.sweepCompleted(true);
            log.error("Reconciliation sweep failed", ex);
            throw ex;
        }
    }

    /** @return how many payments were examined, for tests and for the log line. */
    public int reconcilePayments() {
        Instant createdBefore = Instant.now().minus(properties.getReconciliationGrace());
        List<Payment> candidates = payments.findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
                RECONCILABLE, createdBefore, PageRequest.of(0, properties.getReconciliationBatchSize()))
                .stream()
                // A capture is confirmed against the provider once; after that
                // its ledger entries are immutable and nothing about it can
                // change without a webhook that would re-open it.
                .filter(payment -> payment.getStatus() != PaymentStatus.SUCCEEDED || payment.getReconciledAt() == null)
                .toList();

        int examined = 0;
        for (Payment payment : candidates) {
            if (reconcileOne(payment)) {
                examined++;
            }
        }
        if (examined > 0) {
            log.info("Reconciled {} payment(s) against Paystack", examined);
        }
        return examined;
    }

    /** @return true if the provider actually answered, false if it could not be reached. */
    boolean reconcileOne(Payment payment) {
        Optional<PaystackTransaction> providerView;
        try {
            providerView = paystackClient.verifyTransaction(payment.getReference());
        } catch (PaystackClientException ex) {
            // Not a divergence — we learned nothing. Leaving reconciledAt unset
            // means the next sweep picks it up again; recording a finding per
            // unreachable payment would turn a ten-minute outage into a
            // discrepancy table nobody can read.
            log.warn("Could not reconcile payment reference={}: {}", payment.getReference(), ex.getMessage());
            metrics.providerUnreachable();
            return false;
        }

        if (providerView.isEmpty()) {
            discrepancies.record(DiscrepancyType.MISSING_PROVIDER_RECORD, payment.getReference(),
                    payment.getStatus().name(), "no record",
                    "Paystack has no record of a payment the platform created");
            stampReconciled(payment);
            return true;
        }

        PaystackTransaction provider = providerView.get();
        PaymentStatus providerStatus = toPaymentStatus(provider.status());

        if (providerStatus == PaymentStatus.SUCCEEDED && !payment.getStatus().isCaptured()) {
            // The webhook never arrived, or arrived and was rejected. Money
            // exists that the books do not account for; the fix is to replay
            // the event (which the ledger will then apply exactly once), and
            // that is an operator's call, not a sweep's.
            discrepancies.record(DiscrepancyType.PROVIDER_STATUS_DIVERGENCE, payment.getReference(),
                    payment.getStatus().name(), provider.status(),
                    "Paystack reports this charge as successful but the platform has not captured it; "
                            + "replay the charge.success webhook from the Paystack dashboard to post it");
            stampReconciled(payment);
            return true;
        }

        if (isFinalFailure(providerStatus) && payment.getStatus().isCaptured()) {
            discrepancies.record(DiscrepancyType.PROVIDER_STATUS_DIVERGENCE, payment.getReference(),
                    payment.getStatus().name(), provider.status(),
                    "The platform's books hold a captured charge that Paystack reports as " + provider.status());
            stampReconciled(payment);
            return true;
        }

        if (isFinalFailure(providerStatus) && !payment.getStatus().isCaptured()
                && payment.getStatus().canTransitionTo(providerStatus)) {
            // No money either side: this is news, not a correction. See class Javadoc.
            payment.setStatus(providerStatus);
            payment.setUpdatedAt(Instant.now());
            payment.setFailureReason("Closed by reconciliation: Paystack reports " + provider.status());
            stampReconciled(payment);
            transactionService.projectFromPayment(payment);
            log.info("Payment reference={} closed as {} by reconciliation", payment.getReference(), providerStatus);
            return true;
        }

        if (payment.getStatus().isCaptured() && provider.amountMinor() != payment.getCapturedAmountMinor()) {
            discrepancies.record(DiscrepancyType.AMOUNT_DIVERGENCE, payment.getReference(),
                    String.valueOf(payment.getCapturedAmountMinor()), String.valueOf(provider.amountMinor()),
                    "Captured amount in the ledger differs from the amount Paystack reports");
        }

        stampReconciled(payment);
        return true;
    }

    /**
     * Checks the invariant the whole design rests on. If this ever fires, a
     * posting rule is wrong, and every figure derived from the ledger is
     * suspect until someone looks — so it is checked on a schedule and not
     * only in tests.
     */
    public void assertLedgerBalances() {
        for (String currency : ledger.currencies()) {
            LedgerService.TrialBalance balance = ledger.trialBalance(currency);
            if (!balance.balanced()) {
                discrepancies.record(DiscrepancyType.LEDGER_IMBALANCE, currency,
                        "debits " + balance.debitsMinor(), "credits " + balance.creditsMinor(),
                        "Ledger does not balance in " + currency + "; difference "
                                + balance.differenceMinor() + " minor units");
            }
        }
    }

    // --- operator surface ---------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReconciliationDiscrepancy> listDiscrepancies(DiscrepancyStatus status, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return discrepancyRepository.findByStatusOrderByDetectedAtDesc(status, PageRequest.of(0, capped));
    }

    @Transactional(readOnly = true)
    public List<ReconciliationDiscrepancy> discrepanciesFor(String resourceReference) {
        return discrepancyRepository.findByResourceReferenceOrderByDetectedAtDesc(resourceReference);
    }

    /**
     * Records that an operator has looked at a finding. Note what this cannot
     * do: it does not touch the ledger, the payment, or the finding's
     * observed values. Closing a finding is a statement about who has dealt
     * with it, never a correction of what it observed.
     */
    @Transactional
    public ReconciliationDiscrepancy updateDiscrepancy(Long id, DiscrepancyStatus status, String resolutionNote) {
        ReconciliationDiscrepancy discrepancy = discrepancyRepository.findById(id)
                .orElseThrow(() -> new DiscrepancyNotFoundException(id));
        discrepancy.setStatus(status);
        discrepancy.setResolutionNote(resolutionNote);
        discrepancy.setUpdatedAt(Instant.now());
        log.info("Reconciliation discrepancy id={} moved to {} by an operator", id, status);
        return discrepancyRepository.save(discrepancy);
    }

    private void stampReconciled(Payment payment) {
        payment.setReconciledAt(Instant.now());
        payments.save(payment);
    }

    private static boolean isFinalFailure(PaymentStatus status) {
        return status == PaymentStatus.FAILED || status == PaymentStatus.ABANDONED;
    }

    /**
     * Maps Paystack's status vocabulary onto the platform's. Anything
     * unrecognised is read as {@link PaymentStatus#PENDING} — "we don't know
     * yet" is the only safe reading of a status this code has never seen, and
     * it means a new provider status can't be mistaken for a terminal outcome.
     */
    static PaymentStatus toPaymentStatus(String providerStatus) {
        if (providerStatus == null) {
            return PaymentStatus.PENDING;
        }
        switch (providerStatus.toLowerCase(Locale.ROOT)) {
            case "success":
                return PaymentStatus.SUCCEEDED;
            case "failed":
                return PaymentStatus.FAILED;
            case "abandoned":
                return PaymentStatus.ABANDONED;
            case "reversed":
                return PaymentStatus.REVERSED;
            default:
                return PaymentStatus.PENDING;
        }
    }
}
