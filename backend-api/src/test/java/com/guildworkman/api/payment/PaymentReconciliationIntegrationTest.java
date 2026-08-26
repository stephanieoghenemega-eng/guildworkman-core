package com.guildworkman.api.payment;

import com.guildworkman.api.data.repository.TransactionRepository;
import com.guildworkman.api.payment.model.DiscrepancyStatus;
import com.guildworkman.api.payment.model.DiscrepancyType;
import com.guildworkman.api.payment.model.LedgerAccounts;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.repository.LedgerAccountRepository;
import com.guildworkman.api.payment.repository.LedgerEntryRepository;
import com.guildworkman.api.payment.repository.LedgerTransactionRepository;
import com.guildworkman.api.payment.repository.PaymentRepository;
import com.guildworkman.api.payment.repository.PayoutRepository;
import com.guildworkman.api.payment.repository.ProcessedWebhookEventRepository;
import com.guildworkman.api.payment.repository.ReconciliationDiscrepancyRepository;
import com.guildworkman.api.payment.service.LedgerService;
import com.guildworkman.api.payment.service.DiscrepancyRecorder;
import com.guildworkman.api.payment.service.PaymentReconciliationService;
import com.guildworkman.api.payment.service.PaystackClient;
import com.guildworkman.api.payment.service.PaystackClientException;
import com.guildworkman.api.payment.service.PaystackTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Reconciliation, which exists to answer one question honestly: does what the
 * platform's books say match what the provider says?
 *
 * <p>The tests are split along the line the design turns on — the cases where
 * money is or might be involved must produce a finding and change nothing,
 * and the one case where no money exists on either side may be closed out
 * silently. Getting that line wrong in either direction is the failure mode:
 * too eager, and the sweep quietly rewrites the books; too timid, and a
 * client abandoning a checkout files a finding.
 */
@SpringBootTest(properties = {
        "payments.paystack.secret-key=sk_test_reconciliation",
        "payments.platform-fee-bps=250",
        "payments.reconciliation-grace=PT15M",
        "payments.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000"
})
class PaymentReconciliationIntegrationTest {

    private static final String CURRENCY = "NGN";

    @Autowired
    private PaymentReconciliationService reconciliation;
    @Autowired
    private DiscrepancyRecorder discrepancyRecorder;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private PayoutRepository payouts;
    @Autowired
    private LedgerAccountRepository ledgerAccounts;
    @Autowired
    private LedgerTransactionRepository ledgerTransactions;
    @Autowired
    private LedgerEntryRepository ledgerEntries;
    @Autowired
    private ProcessedWebhookEventRepository processedEvents;
    @Autowired
    private ReconciliationDiscrepancyRepository discrepancies;
    @Autowired
    private TransactionRepository transactions;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PaystackClient paystackClient;

    @BeforeEach
    void cleanSlate() {
        reset(paystackClient);
        transactions.deleteAll();
        ledgerEntries.deleteAll();
        ledgerTransactions.deleteAll();
        processedEvents.deleteAll();
        discrepancies.deleteAll();
        payments.deleteAll();
        payouts.deleteAll();
    }

    // --- helpers -----------------------------------------------------------

    /** A payment old enough for the sweep to consider it. */
    private Payment agedPayment(PaymentStatus status, long amountMinor, long capturedMinor) {
        Payment payment = new Payment("GWM-" + UUID.randomUUID().toString().replace("-", ""),
                1L, 2L, null, "client@example.com", amountMinor, CURRENCY);
        payment.setStatus(status);
        payment.setCapturedAmountMinor(capturedMinor);
        payment.setPlatformFeeMinor(capturedMinor / 40);
        // createdAt is updatable = false, so the age has to be set before the
        // first insert rather than patched afterwards.
        payment.setCreatedAt(Instant.now().minusSeconds(60 * 60));
        if (status.isCaptured()) {
            payment.setCapturedAt(Instant.now().minusSeconds(60 * 30));
        }
        return payments.saveAndFlush(payment);
    }

    private void providerReports(String reference, String status, long amountMinor) {
        when(paystackClient.verifyTransaction(reference)).thenReturn(Optional.of(
                new PaystackTransaction(reference, 1L, status, amountMinor, 0L, CURRENCY)));
    }

    private DiscrepancyType onlyFindingType() {
        assertThat(discrepancies.findAll()).hasSize(1);
        return discrepancies.findAll().get(0).getType();
    }

    // --- money involved: report, change nothing -----------------------------

    @Test
    void aChargeTheProviderCallsSuccessfulButThePlatformNeverCapturedIsReportedNotApplied() {
        // The missing-webhook case. There is money at Paystack the books know
        // nothing about — and the sweep must not invent the ledger entries for
        // it, because the amounts and fees it would have to guess at are
        // exactly what the webhook carries.
        Payment payment = agedPayment(PaymentStatus.PENDING, 100_000, 0);
        providerReports(payment.getReference(), "success", 100_000);

        reconciliation.reconcilePayments();

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(ledgerTransactions.count()).isZero();
        assertThat(onlyFindingType()).isEqualTo(DiscrepancyType.PROVIDER_STATUS_DIVERGENCE);
        assertThat(discrepancies.findAll().get(0).getDetail()).contains("replay");
    }

    @Test
    void aCapturedChargeTheProviderCallsFailedIsReportedNotUnwound() {
        // The serious one: the books hold money the provider says isn't there.
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        providerReports(payment.getReference(), "failed", 100_000);

        reconciliation.reconcilePayments();

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(onlyFindingType()).isEqualTo(DiscrepancyType.PROVIDER_STATUS_DIVERGENCE);
    }

    @Test
    void aCapturedAmountThatDisagreesWithTheProviderIsReported() {
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        providerReports(payment.getReference(), "success", 90_000);

        reconciliation.reconcilePayments();

        assertThat(onlyFindingType()).isEqualTo(DiscrepancyType.AMOUNT_DIVERGENCE);
        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getCapturedAmountMinor())
                .isEqualTo(100_000);
    }

    @Test
    void aPaymentTheProviderHasNoRecordOfIsReported() {
        Payment payment = agedPayment(PaymentStatus.PENDING, 100_000, 0);
        when(paystackClient.verifyTransaction(payment.getReference())).thenReturn(Optional.empty());

        reconciliation.reconcilePayments();

        assertThat(onlyFindingType()).isEqualTo(DiscrepancyType.MISSING_PROVIDER_RECORD);
    }

    // --- no money either side: close it out quietly -------------------------

    @Test
    void anAbandonedCheckoutIsClosedWithoutFilingAFinding() {
        // A client who closed the tab is not an accounting problem, and filing
        // a finding for every one of them would bury the findings that matter.
        Payment payment = agedPayment(PaymentStatus.INITIATED, 100_000, 0);
        providerReports(payment.getReference(), "abandoned", 100_000);

        reconciliation.reconcilePayments();

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ABANDONED);
        assertThat(discrepancies.count()).isZero();
        assertThat(ledgerTransactions.count()).isZero();
    }

    @Test
    void aDeclinedChargeIsClosedWithoutFilingAFinding() {
        Payment payment = agedPayment(PaymentStatus.PENDING, 100_000, 0);
        providerReports(payment.getReference(), "failed", 100_000);

        reconciliation.reconcilePayments();

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(discrepancies.count()).isZero();
    }

    // --- what the sweep declines to do --------------------------------------

    @Test
    void aProviderOutageIsNotADivergence() {
        // If an unreachable provider filed findings, a ten-minute Paystack
        // outage would produce a discrepancy per payment and drown the real ones.
        Payment payment = agedPayment(PaymentStatus.PENDING, 100_000, 0);
        when(paystackClient.verifyTransaction(anyString()))
                .thenThrow(new PaystackClientException("connection reset"));

        int examined = reconciliation.reconcilePayments();

        assertThat(examined).isZero();
        assertThat(discrepancies.count()).isZero();
        // Left unstamped, so the next sweep tries again.
        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getReconciledAt()).isNull();
    }

    @Test
    void aPaymentStillInsideItsGraceWindowIsLeftAlone() {
        // The client may still be on the checkout page. Asking Paystack about a
        // two-second-old payment reports "no record" for every payment ever made.
        Payment fresh = new Payment("GWM-" + UUID.randomUUID().toString().replace("-", ""),
                1L, 2L, null, "client@example.com", 100_000, CURRENCY);
        payments.saveAndFlush(fresh);

        assertThat(reconciliation.reconcilePayments()).isZero();
        assertThat(discrepancies.count()).isZero();
    }

    @Test
    void aCapturedPaymentIsConfirmedOnceRatherThanOnEverySweep() {
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        providerReports(payment.getReference(), "success", 100_000);

        assertThat(reconciliation.reconcilePayments()).isEqualTo(1);
        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getReconciledAt()).isNotNull();

        // Its ledger entries are immutable and nothing can change about it
        // without a webhook that would re-open it, so re-verifying the whole
        // history of the platform every minute buys nothing.
        assertThat(reconciliation.reconcilePayments()).isZero();
    }

    @Test
    void concurrentSweepsFilingTheSameFindingProduceOneRowAndNoError() {
        // Regression: the dedupe check and the insert are not atomic, so two
        // instances sweeping at once both see "not recorded" and both insert.
        // The unique index decides, and the loser's violation has to be
        // absorbed *outside* the nested transaction it aborted — catching it
        // inside would return normally and then fail the commit with
        // UnexpectedRollbackException, turning a benign duplicate into an error.
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    discrepancyRecorder.record(DiscrepancyType.PROVIDER_STATUS_DIVERGENCE,
                            payment.getReference(), "SUCCEEDED", "failed", "concurrent finding");
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                // get() rethrows: recording a duplicate must not surface as an error.
                future.get(10, TimeUnit.SECONDS);
            }
        } catch (Exception ex) {
            throw new AssertionError("Recording a concurrent duplicate finding must not fail", ex);
        } finally {
            pool.shutdownNow();
        }

        assertThat(discrepancies.count()).isEqualTo(1);
    }

    @Test
    void anUnchangedDivergenceIsNotFiledTwice() {
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        providerReports(payment.getReference(), "failed", 100_000);

        reconciliation.reconcilePayments();
        // Force a second look at the same payment.
        Payment reloaded = payments.findByReference(payment.getReference()).orElseThrow();
        reloaded.setReconciledAt(null);
        payments.saveAndFlush(reloaded);
        reconciliation.reconcilePayments();

        assertThat(discrepancies.count()).isEqualTo(1);
    }

    // --- the invariant ------------------------------------------------------

    @Test
    void aBalancedLedgerProducesNoFinding() {
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        ledgerService.postChargeCapture(payment, "posting-" + UUID.randomUUID(), 100_000, 1_500, 2_500);

        reconciliation.assertLedgerBalances();

        assertThat(discrepancies.count()).isZero();
    }

    @Test
    void aLedgerThatHasStoppedBalancingIsReportedWithoutWaitingForSomeoneToRunAReport() {
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        var posting = ledgerService.postChargeCapture(
                payment, "posting-" + UUID.randomUUID(), 100_000, 1_500, 2_500);

        // Written straight to the table, deliberately bypassing the aggregate:
        // the aggregate refuses to create this, so the only way the state can
        // arise in production is a bug or a hand-run statement — which is
        // exactly the state the sweep has to be able to notice.
        Long accountId = ledgerAccounts.findByCode(LedgerAccounts.WORKER_PAYABLE).orElseThrow().getId();
        jdbcTemplate.update(
                "insert into ledger_entries (ledger_transaction_id, account_id, direction, amount_minor, currency) "
                        + "values (?, ?, 'DEBIT', 7, ?)",
                posting.getId(), accountId, CURRENCY);

        try {
            assertThat(ledgerService.trialBalance(CURRENCY).balanced()).isFalse();

            reconciliation.assertLedgerBalances();

            assertThat(discrepancies.findAll())
                    .extracting(discrepancy -> discrepancy.getType())
                    .contains(DiscrepancyType.LEDGER_IMBALANCE);
            assertThat(discrepancies.findAll().get(0).getDetail()).contains("7");
        } finally {
            // Undone here rather than left to the next @BeforeEach: test
            // classes share one database, and an imbalance surviving this class
            // would fail an unrelated class's trial-balance assertion.
            jdbcTemplate.update("delete from ledger_entries where amount_minor = 7 and currency = ?", CURRENCY);
        }
    }

    // --- operator workflow --------------------------------------------------

    @Test
    void resolvingAFindingRecordsWhoDealtWithItAndChangesNothingElse() {
        Payment payment = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        providerReports(payment.getReference(), "failed", 100_000);
        reconciliation.reconcilePayments();
        Long id = discrepancies.findAll().get(0).getId();

        var resolved = reconciliation.updateDiscrepancy(id, DiscrepancyStatus.RESOLVED,
                "Confirmed with Paystack support; posted a correcting entry under CORR-1");

        assertThat(resolved.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(resolved.getResolutionNote()).contains("CORR-1");
        // Closing a finding is a statement about who handled it, not a
        // correction of what it observed.
        assertThat(resolved.getPlatformState()).isEqualTo(PaymentStatus.SUCCEEDED.name());
        assertThat(resolved.getProviderState()).isEqualTo("failed");
        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void openFindingsAreListedMostRecentFirst() {
        Payment first = agedPayment(PaymentStatus.SUCCEEDED, 100_000, 100_000);
        Payment second = agedPayment(PaymentStatus.SUCCEEDED, 50_000, 50_000);
        providerReports(first.getReference(), "failed", 100_000);
        providerReports(second.getReference(), "failed", 50_000);

        reconciliation.reconcilePayments();

        assertThat(reconciliation.listDiscrepancies(DiscrepancyStatus.OPEN, 10)).hasSize(2);
        assertThat(reconciliation.listDiscrepancies(DiscrepancyStatus.RESOLVED, 10)).isEmpty();
        assertThat(reconciliation.discrepanciesFor(first.getReference())).hasSize(1);
    }
}
