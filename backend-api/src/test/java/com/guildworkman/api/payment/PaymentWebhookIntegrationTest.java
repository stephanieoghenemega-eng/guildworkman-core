package com.guildworkman.api.payment;

import com.guildworkman.api.data.constants.TransactionStatus;
import com.guildworkman.api.data.repository.TransactionRepository;
import com.guildworkman.api.payment.model.DiscrepancyType;
import com.guildworkman.api.payment.model.LedgerAccounts;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.model.PayoutStatus;
import com.guildworkman.api.payment.model.WebhookOutcome;
import com.guildworkman.api.payment.repository.LedgerEntryRepository;
import com.guildworkman.api.payment.repository.LedgerTransactionRepository;
import com.guildworkman.api.payment.repository.PaymentRepository;
import com.guildworkman.api.payment.repository.PayoutRepository;
import com.guildworkman.api.payment.repository.ProcessedWebhookEventRepository;
import com.guildworkman.api.payment.repository.ReconciliationDiscrepancyRepository;
import com.guildworkman.api.payment.service.InvalidWebhookSignatureException;
import com.guildworkman.api.payment.service.LedgerService;
import com.guildworkman.api.payment.service.PaymentInitiationService;
import com.guildworkman.api.payment.service.PaystackClient;
import com.guildworkman.api.payment.service.PaystackInitialization;
import com.guildworkman.api.payment.service.PaystackSignatureVerifier;
import com.guildworkman.api.payment.service.PaystackWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end behaviour of the money path, against a real database.
 *
 * <p>These are the properties the feature is actually for, and each test is
 * named after one: a forged signature changes nothing; a redelivered event
 * credits once; the books balance after every scenario; a payment completes
 * without the client ever coming back; an event that doesn't fit the
 * lifecycle is refused and reported instead of applied.
 *
 * <p>Paystack itself is mocked at the client boundary — the wire format is
 * covered against a real HTTP server in {@code PaystackClientTest}. What is
 * <em>not</em> mocked is the signature: every webhook here is signed the way
 * Paystack signs one, so the verification path is exercised on every call.
 */
@SpringBootTest(properties = {
        "payments.paystack.secret-key=" + PaymentWebhookIntegrationTest.SECRET,
        "payments.platform-fee-bps=250",
        "payments.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000"
})
class PaymentWebhookIntegrationTest {

    static final String SECRET = "sk_test_integration_secret";
    private static final String CURRENCY = "NGN";

    /** Distinct provider ids per event so the derived idempotency keys don't collide across tests. */
    private static final AtomicLong PROVIDER_ID = new AtomicLong(1_000);

    @Autowired
    private PaystackWebhookService webhookService;
    @Autowired
    private PaymentInitiationService initiationService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private PayoutRepository payouts;
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

    @MockBean
    private PaystackClient paystackClient;

    @BeforeEach
    void cleanSlate() {
        reset(paystackClient);
        // Ledger accounts are deliberately not cleared: they are seeded once at
        // startup and every posting rule resolves them by code.
        transactions.deleteAll();
        ledgerEntries.deleteAll();
        ledgerTransactions.deleteAll();
        processedEvents.deleteAll();
        discrepancies.deleteAll();
        payments.deleteAll();
        payouts.deleteAll();
    }

    // --- helpers -----------------------------------------------------------

    private Payment initializedPayment(BigDecimal amount) {
        when(paystackClient.initializeTransaction(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenAnswer(invocation -> new PaystackInitialization(
                        "https://checkout.paystack.com/test", "access-code", invocation.getArgument(0)));
        return initiationService.initiate(1L, 2L, 3L, "client@example.com", amount, CURRENCY);
    }

    /** Signs the body the way Paystack does and pushes it through the real webhook path. */
    private WebhookOutcome deliver(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return webhookService.handle(body, PaystackSignatureVerifier.hexMac(body, SECRET));
    }

    private static String chargeSuccess(String reference, long amountMinor, long feesMinor) {
        return chargeSuccess(reference, amountMinor, feesMinor, PROVIDER_ID.incrementAndGet());
    }

    private static String chargeSuccess(String reference, long amountMinor, long feesMinor, long providerId) {
        return "{\"event\":\"charge.success\",\"data\":{\"id\":" + providerId
                + ",\"status\":\"success\",\"reference\":\"" + reference + "\",\"amount\":" + amountMinor
                + ",\"fees\":" + feesMinor + ",\"currency\":\"" + CURRENCY
                + "\",\"gateway_response\":\"Successful\"}}";
    }

    private static String chargeFailed(String reference, long amountMinor) {
        return "{\"event\":\"charge.failed\",\"data\":{\"id\":" + PROVIDER_ID.incrementAndGet()
                + ",\"status\":\"failed\",\"reference\":\"" + reference + "\",\"amount\":" + amountMinor
                + ",\"currency\":\"" + CURRENCY + "\",\"gateway_response\":\"Insufficient funds\"}}";
    }

    private static String refundProcessed(String transactionReference, long amountMinor) {
        return "{\"event\":\"refund.processed\",\"data\":{\"id\":" + PROVIDER_ID.incrementAndGet()
                + ",\"status\":\"processed\",\"transaction_reference\":\"" + transactionReference
                + "\",\"amount\":" + amountMinor + ",\"currency\":\"" + CURRENCY + "\"}}";
    }

    private static String transferEvent(String type, String reference, long amountMinor, long feesMinor) {
        return "{\"event\":\"" + type + "\",\"data\":{\"id\":" + PROVIDER_ID.incrementAndGet()
                + ",\"status\":\"" + type.substring(type.indexOf('.') + 1) + "\",\"reference\":\"" + reference
                + "\",\"amount\":" + amountMinor + ",\"fees\":" + feesMinor + ",\"currency\":\"" + CURRENCY
                + "\",\"reason\":\"payout\",\"metadata\":{\"skilledWorkerId\":2}}}";
    }

    private long balance(String accountCode) {
        return ledgerService.balanceOf(accountCode, CURRENCY);
    }

    private void assertBooksBalance() {
        LedgerService.TrialBalance trialBalance = ledgerService.trialBalance(CURRENCY);
        assertThat(trialBalance.balanced())
                .as("trial balance: debits %s vs credits %s",
                        trialBalance.debitsMinor(), trialBalance.creditsMinor())
                .isTrue();
    }

    // --- capture -----------------------------------------------------------

    @Test
    void aSuccessfulChargeCapturesThePaymentAndPostsBalancedEntries() {
        Payment payment = initializedPayment(new BigDecimal("1500.00"));

        assertThat(deliver(chargeSuccess(payment.getReference(), 150_000, 2_250))).isEqualTo(WebhookOutcome.APPLIED);

        Payment captured = payments.findByReference(payment.getReference()).orElseThrow();
        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(captured.getCapturedAmountMinor()).isEqualTo(150_000);
        assertThat(captured.getProviderFeeMinor()).isEqualTo(2_250);
        assertThat(captured.getPlatformFeeMinor()).isEqualTo(3_750);   // 2.5% of 150000
        assertThat(captured.getCapturedAt()).isNotNull();

        // Gross is booked, and the provider's fee is expensed rather than
        // netted away, so the books still show what the client was charged.
        assertThat(balance(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK)).isEqualTo(147_750);
        assertThat(balance(LedgerAccounts.PROVIDER_FEE_EXPENSE)).isEqualTo(2_250);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isEqualTo(146_250);
        assertThat(balance(LedgerAccounts.PLATFORM_FEE_REVENUE)).isEqualTo(3_750);
        assertBooksBalance();
    }

    @Test
    void aPaymentCompletesEvenWhenTheClientNeverReturnsFromTheRedirect() {
        // The client is handed a checkout URL and is never heard from again —
        // no callback, no status poll. This is the flow the redirect-callback
        // approach lost payments on.
        Payment payment = initializedPayment(new BigDecimal("500.00"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);

        deliver(chargeSuccess(payment.getReference(), 50_000, 750));

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(transactions.findByPaymentReference(payment.getReference()).orElseThrow()
                .getTransactionStatus()).isEqualTo(TransactionStatus.PAID);
        assertBooksBalance();
    }

    @Test
    void theDerivedTransactionViewFollowsThePaymentThroughItsLifecycle() {
        Payment payment = initializedPayment(new BigDecimal("200.00"));
        assertThat(transactions.findByPaymentReference(payment.getReference()).orElseThrow()
                .getTransactionStatus()).isEqualTo(TransactionStatus.PENDING);

        deliver(chargeSuccess(payment.getReference(), 20_000, 300));
        assertThat(transactions.findByPaymentReference(payment.getReference()).orElseThrow()
                .getTransactionStatus()).isEqualTo(TransactionStatus.PAID);

        deliver(refundProcessed(payment.getReference(), 20_000));
        assertThat(transactions.findByPaymentReference(payment.getReference()).orElseThrow()
                .getTransactionStatus()).isEqualTo(TransactionStatus.REFUNDED);

        // One row throughout: the projection is refreshed, not appended to.
        assertThat(transactions.count()).isEqualTo(1);
    }

    // --- idempotency -------------------------------------------------------

    @Test
    void aRedeliveredEventProducesExactlyOneLedgerEffect() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        String event = chargeSuccess(payment.getReference(), 100_000, 1_500);

        assertThat(deliver(event)).isEqualTo(WebhookOutcome.APPLIED);
        assertThat(deliver(event)).isEqualTo(WebhookOutcome.DUPLICATE);
        assertThat(deliver(event)).isEqualTo(WebhookOutcome.DUPLICATE);

        assertThat(ledgerTransactions.count()).isEqualTo(1);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isEqualTo(97_500);
        assertBooksBalance();
    }

    @Test
    void concurrentDeliveriesOfTheSameEventProduceExactlyOneLedgerEffect() throws Exception {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        String event = chargeSuccess(payment.getReference(), 100_000, 1_500);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<WebhookOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deliver(event));
        }

        List<Future<WebhookOutcome>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        List<WebhookOutcome> outcomes = new ArrayList<>();
        for (Future<WebhookOutcome> future : futures) {
            // get() rethrows anything a delivery threw: a duplicate must be
            // answered, not turned into a 500 the provider will retry.
            outcomes.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(outcomes).filteredOn(outcome -> outcome == WebhookOutcome.APPLIED).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome == WebhookOutcome.DUPLICATE).hasSize(threads - 1);
        assertThat(ledgerTransactions.count()).isEqualTo(1);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isEqualTo(97_500);
        assertBooksBalance();
    }

    // --- signature ---------------------------------------------------------

    @Test
    void aForgedSignatureIsRejectedAndNeverMutatesLedgerState() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        byte[] body = chargeSuccess(payment.getReference(), 100_000, 1_500).getBytes(StandardCharsets.UTF_8);
        String forged = PaystackSignatureVerifier.hexMac(body, "sk_test_wrong_secret");

        assertThatThrownBy(() -> webhookService.handle(body, forged))
                .isInstanceOf(InvalidWebhookSignatureException.class);

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.INITIATED);
        assertThat(ledgerTransactions.count()).isZero();
        assertThat(ledgerEntries.count()).isZero();
        // Not even the audit row: an unverified payload is never taken
        // responsibility for.
        assertThat(processedEvents.count()).isZero();
    }

    @Test
    void aMissingSignatureIsRejectedAndNeverMutatesLedgerState() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        byte[] body = chargeSuccess(payment.getReference(), 100_000, 1_500).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> webhookService.handle(body, null))
                .isInstanceOf(InvalidWebhookSignatureException.class);

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.INITIATED);
        assertThat(ledgerTransactions.count()).isZero();
    }

    // --- refunds -----------------------------------------------------------

    @Test
    void aFullRefundReturnsTheCommissionAndLeavesEveryAffectedAccountAtZero() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        deliver(chargeSuccess(payment.getReference(), 100_000, 1_500));

        assertThat(deliver(refundProcessed(payment.getReference(), 100_000))).isEqualTo(WebhookOutcome.APPLIED);

        Payment refunded = payments.findByReference(payment.getReference()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.getRefundedAmountMinor()).isEqualTo(100_000);

        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isZero();
        assertThat(balance(LedgerAccounts.PLATFORM_FEE_REVENUE)).isZero();
        // The provider keeps its fee on a refund, so the platform is down by it
        // — an accurate statement of what happened, not an imbalance.
        assertThat(balance(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK)).isEqualTo(-1_500);
        assertThat(balance(LedgerAccounts.PROVIDER_FEE_EXPENSE)).isEqualTo(1_500);
        assertBooksBalance();

        // Two postings, not one edited: the capture is still there.
        assertThat(ledgerTransactions.count()).isEqualTo(2);
    }

    @Test
    void aPartialRefundLeavesTheRestOfTheMoneyWhereItWas() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        deliver(chargeSuccess(payment.getReference(), 100_000, 1_500));

        assertThat(deliver(refundProcessed(payment.getReference(), 40_000))).isEqualTo(WebhookOutcome.APPLIED);

        Payment refunded = payments.findByReference(payment.getReference()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(refunded.netCapturedMinor()).isEqualTo(60_000);

        // 2.5% of 40000 = 1000 of commission handed back with it.
        assertThat(balance(LedgerAccounts.PLATFORM_FEE_REVENUE)).isEqualTo(1_500);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isEqualTo(58_500);
        assertBooksBalance();
    }

    @Test
    void refundingInAwkwardInstalmentsStillReturnsTheCommissionExactly() {
        // The rounding case: 99999 at 2.5% is a commission of 2499, and a
        // per-instalment floor would lose a unit or two on the way back and
        // leave the worker's payable account slightly negative.
        Payment payment = initializedPayment(new BigDecimal("999.99"));
        deliver(chargeSuccess(payment.getReference(), 99_999, 1_499));

        deliver(refundProcessed(payment.getReference(), 33_333));
        deliver(refundProcessed(payment.getReference(), 33_333));
        deliver(refundProcessed(payment.getReference(), 33_333));

        Payment refunded = payments.findByReference(payment.getReference()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isZero();
        assertThat(balance(LedgerAccounts.PLATFORM_FEE_REVENUE)).isZero();
        assertBooksBalance();
    }

    @Test
    void aRefundLargerThanWhatIsLeftIsRefusedAndFlagged() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        deliver(chargeSuccess(payment.getReference(), 100_000, 1_500));

        assertThat(deliver(refundProcessed(payment.getReference(), 150_000))).isEqualTo(WebhookOutcome.REJECTED);

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getRefundedAmountMinor())
                .isZero();
        assertThat(ledgerTransactions.count()).isEqualTo(1);
        assertThat(discrepancies.findAll())
                .extracting(d -> d.getType())
                .contains(DiscrepancyType.AMOUNT_DIVERGENCE);
        assertBooksBalance();
    }

    // --- out-of-order and illegal events -----------------------------------

    @Test
    void aRefundThatOvertakesItsChargeIsRefusedAndRecordedRatherThanApplied() {
        // The event ordering Paystack does not promise. Applying this would
        // post a refund against money the books say was never collected: the
        // entry would balance and describe something that never happened.
        Payment payment = initializedPayment(new BigDecimal("1000.00"));

        assertThat(deliver(refundProcessed(payment.getReference(), 100_000))).isEqualTo(WebhookOutcome.REJECTED);

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.INITIATED);
        assertThat(ledgerTransactions.count()).isZero();
        assertThat(discrepancies.findAll())
                .singleElement()
                .satisfies(discrepancy -> {
                    assertThat(discrepancy.getType()).isEqualTo(DiscrepancyType.ILLEGAL_TRANSITION);
                    assertThat(discrepancy.getResourceReference()).isEqualTo(payment.getReference());
                });
        // Recorded, so the provider stops retrying an event that will never
        // become legal.
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void aSecondDistinctSuccessForAnAlreadyCapturedPaymentIsRefused() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        deliver(chargeSuccess(payment.getReference(), 100_000, 1_500, 5_001));

        // Different provider id, so this is not a redelivery — it is the
        // provider claiming a second capture, which cannot be reconciled.
        assertThat(deliver(chargeSuccess(payment.getReference(), 100_000, 1_500, 5_002)))
                .isEqualTo(WebhookOutcome.REJECTED);

        assertThat(ledgerTransactions.count()).isEqualTo(1);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isEqualTo(97_500);
        assertBooksBalance();
    }

    @Test
    void anEventForAReferenceThePlatformNeverIssuedIsRefusedAndFlagged() {
        assertThat(deliver(chargeSuccess("GWM-never-issued", 100_000, 1_500))).isEqualTo(WebhookOutcome.REJECTED);

        assertThat(ledgerTransactions.count()).isZero();
        assertThat(discrepancies.findAll())
                .extracting(d -> d.getType())
                .containsExactly(DiscrepancyType.UNKNOWN_REFERENCE);
    }

    @Test
    void aChargeInADifferentCurrencyThanThePaymentIsRefused() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        String mismatched = "{\"event\":\"charge.success\",\"data\":{\"id\":" + PROVIDER_ID.incrementAndGet()
                + ",\"status\":\"success\",\"reference\":\"" + payment.getReference()
                + "\",\"amount\":100000,\"fees\":1500,\"currency\":\"USD\"}}";

        assertThat(deliver(mismatched)).isEqualTo(WebhookOutcome.REJECTED);

        assertThat(ledgerTransactions.count()).isZero();
        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.INITIATED);
    }

    @Test
    void aProviderFeeLargerThanTheChargeIsRefusedRatherThanPostedAsANegativeLine() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));

        assertThat(deliver(chargeSuccess(payment.getReference(), 100_000, 200_000)))
                .isEqualTo(WebhookOutcome.REJECTED);

        assertThat(ledgerTransactions.count()).isZero();
        assertThat(discrepancies.findAll())
                .extracting(d -> d.getType())
                .contains(DiscrepancyType.AMOUNT_DIVERGENCE);
    }

    @Test
    void aCaptureForAnUnexpectedAmountIsStillBookedButReported() {
        // Money moved. Refusing to book a real capture because it disagreed
        // with our expectation would put the books further from reality.
        Payment payment = initializedPayment(new BigDecimal("1000.00"));

        assertThat(deliver(chargeSuccess(payment.getReference(), 90_000, 1_350))).isEqualTo(WebhookOutcome.APPLIED);

        assertThat(payments.findByReference(payment.getReference()).orElseThrow().getCapturedAmountMinor())
                .isEqualTo(90_000);
        assertThat(discrepancies.findAll())
                .extracting(d -> d.getType())
                .contains(DiscrepancyType.AMOUNT_DIVERGENCE);
        assertBooksBalance();
    }

    // --- failures ----------------------------------------------------------

    @Test
    void aFailedChargeMovesThePaymentToFailedWithoutTouchingTheLedger() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));

        assertThat(deliver(chargeFailed(payment.getReference(), 100_000))).isEqualTo(WebhookOutcome.APPLIED);

        Payment failed = payments.findByReference(payment.getReference()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("Insufficient funds");
        // A declined charge moved no money; an entry for it would have to be
        // balanced against something that never existed.
        assertThat(ledgerTransactions.count()).isZero();
    }

    @Test
    void anUnhandledEventTypeIsRecordedAndIgnored() {
        String unknown = "{\"event\":\"invoice.create\",\"data\":{\"id\":" + PROVIDER_ID.incrementAndGet() + "}}";

        assertThat(deliver(unknown)).isEqualTo(WebhookOutcome.IGNORED);

        // Recorded, so the audit trail shows it arrived, and answered 200 so
        // Paystack doesn't retry-then-disable the endpoint over an event type
        // this service simply doesn't act on.
        assertThat(processedEvents.count()).isEqualTo(1);
        assertThat(ledgerTransactions.count()).isZero();
    }

    // --- payouts -----------------------------------------------------------

    @Test
    void aSettledPayoutMovesMoneyOutOfTheWorkersPayableBalance() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        deliver(chargeSuccess(payment.getReference(), 100_000, 1_500));

        assertThat(deliver(transferEvent("transfer.success", "TRF-1", 97_500, 100)))
                .isEqualTo(WebhookOutcome.APPLIED);

        assertThat(payouts.findByReference("TRF-1").orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isZero();
        assertThat(balance(LedgerAccounts.PROVIDER_FEE_EXPENSE)).isEqualTo(1_600);
        assertBooksBalance();
    }

    @Test
    void aReversedPayoutIsUndoneByACompensatingEntryNotByEditingTheOriginal() {
        Payment payment = initializedPayment(new BigDecimal("1000.00"));
        deliver(chargeSuccess(payment.getReference(), 100_000, 1_500));
        deliver(transferEvent("transfer.success", "TRF-1", 97_500, 100));

        assertThat(deliver(transferEvent("transfer.reversed", "TRF-1", 97_500, 100)))
                .isEqualTo(WebhookOutcome.APPLIED);

        assertThat(payouts.findByReference("TRF-1").orElseThrow().getStatus()).isEqualTo(PayoutStatus.REVERSED);
        // Back where it was, via a third posting rather than a deletion.
        assertThat(balance(LedgerAccounts.WORKER_PAYABLE)).isEqualTo(97_500);
        assertThat(ledgerTransactions.count()).isEqualTo(3);
        assertBooksBalance();
    }

    @Test
    void aPayoutReversalThatOvertakesItsSettlementIsRefusedAndFlagged() {
        // There is no payout entry in the books to reverse yet. Applying this
        // would credit the worker's payable balance out of nothing.
        assertThat(deliver(transferEvent("transfer.reversed", "TRF-9", 50_000, 50)))
                .isEqualTo(WebhookOutcome.REJECTED);

        assertThat(payouts.findByReference("TRF-9").orElseThrow().getStatus()).isEqualTo(PayoutStatus.PENDING);
        assertThat(ledgerTransactions.count()).isZero();
        assertThat(discrepancies.findAll())
                .extracting(d -> d.getType())
                .containsExactly(DiscrepancyType.ILLEGAL_TRANSITION);
    }

    @Test
    void aFailedTransferIsRecordedWithoutALedgerEffect() {
        assertThat(deliver(transferEvent("transfer.failed", "TRF-2", 50_000, 50)))
                .isEqualTo(WebhookOutcome.APPLIED);

        assertThat(payouts.findByReference("TRF-2").orElseThrow().getStatus()).isEqualTo(PayoutStatus.FAILED);
        // The payout entry is only written on transfer.success, so a transfer
        // that never left has nothing to undo.
        assertThat(ledgerTransactions.count()).isZero();
    }

    // --- the invariant, across everything ----------------------------------

    @Test
    void theBooksBalanceAfterACompleteChargeRefundPayoutAndReversalSequence() {
        Payment first = initializedPayment(new BigDecimal("1000.00"));
        Payment second = initializedPayment(new BigDecimal("737.13"));

        deliver(chargeSuccess(first.getReference(), 100_000, 1_500));
        deliver(chargeSuccess(second.getReference(), 73_713, 1_105));
        deliver(refundProcessed(first.getReference(), 33_333));
        deliver(transferEvent("transfer.success", "TRF-A", 40_000, 75));
        deliver(transferEvent("transfer.reversed", "TRF-A", 40_000, 75));
        deliver(chargeFailed(second.getReference(), 73_713));   // refused: already captured
        deliver(refundProcessed(second.getReference(), 73_713));

        assertBooksBalance();

        // And the same figure computed the other way round: every account's
        // balance, summed with its natural sign, is zero.
        Map<String, Long> balances = ledgerService.accountBalances(CURRENCY);
        long assetsAndExpenses = balances.get(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK)
                + balances.get(LedgerAccounts.PROVIDER_FEE_EXPENSE);
        long liabilitiesAndRevenue = balances.get(LedgerAccounts.WORKER_PAYABLE)
                + balances.get(LedgerAccounts.PLATFORM_FEE_REVENUE);
        assertThat(assetsAndExpenses).isEqualTo(liabilitiesAndRevenue);
    }
}
