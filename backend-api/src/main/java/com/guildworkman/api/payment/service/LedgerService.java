package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.LedgerAccount;
import com.guildworkman.api.payment.model.LedgerAccounts;
import com.guildworkman.api.payment.model.LedgerDirection;
import com.guildworkman.api.payment.model.LedgerTransaction;
import com.guildworkman.api.payment.model.LedgerTransactionType;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.Payout;
import com.guildworkman.api.payment.repository.LedgerAccountRepository;
import com.guildworkman.api.payment.repository.LedgerEntryRepository;
import com.guildworkman.api.payment.repository.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only thing that writes to the ledger. Every posting rule lives here, in
 * one file, so "what does this platform do to the books when X happens?" has
 * a single answer that can be read top to bottom.
 *
 * <p>All four rules follow the same shape: build a {@link LedgerTransaction},
 * add debit and credit lines, and save. The balance check is not in this
 * class — it is on the aggregate, run from {@code @PrePersist} — so a rule
 * added later cannot forget it (see {@link LedgerTransaction}).
 *
 * <h2>The rules</h2>
 * With gross {@code G}, provider fee {@code PF} and platform commission
 * {@code LF}:
 * <pre>
 * charge capture   DR provider receivable  G - PF     CR worker payable      G - LF
 *                  DR provider fee expense PF         CR platform fee revenue LF
 *
 * refund (R)       DR worker payable       R - LFr    CR provider receivable R
 *                  DR platform fee revenue LFr
 *
 * payout (A, fee F) DR worker payable      A          CR provider receivable A + F
 *                   DR provider fee expense F
 *
 * payout reversal   DR provider receivable A + F      CR worker payable      A
 *                                                     CR provider fee expense F
 * </pre>
 * Each is balanced by construction: the debit total and the credit total are
 * the same expression rearranged.
 *
 * <p><b>The provider's fee is expensed, not netted.</b> A charge could be
 * recorded as simply "we received G - PF" in one line each side, and the
 * books would balance. They would also no longer show what the client was
 * charged, which is the number on the client's receipt and the number any
 * dispute is about.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final PaymentProperties properties;

    /**
     * The platform's commission on an amount, floored.
     *
     * <p>Flooring rather than rounding is deliberate: the remainder stays with
     * the worker. Over many payments that costs the platform a fraction of a
     * kobo per transaction and costs the worker nothing, which is the right
     * way round for a rounding rule nobody will ever look at again.
     */
    public long platformFeeOn(long amountMinor) {
        return Math.floorDiv(Math.multiplyExact(amountMinor, (long) properties.getPlatformFeeBps()), 10_000L);
    }

    /**
     * Posts the capture of a successful charge.
     *
     * @param providerFeeMinor Paystack's fee, already validated by the caller
     *                         to be within {@code [0, grossMinor]} — see
     *                         {@code PaystackWebhookProcessor}, which turns an
     *                         out-of-range fee into a discrepancy rather than
     *                         letting it produce a negative ledger line.
     */
    @Transactional
    public LedgerTransaction postChargeCapture(Payment payment, String reference,
                                               long grossMinor, long providerFeeMinor, long platformFeeMinor) {
        LedgerTransaction posting = new LedgerTransaction(reference, LedgerTransactionType.CHARGE_CAPTURE,
                payment.getCurrency(), "Charge captured for payment " + payment.getReference(),
                payment.getReference(), null)
                .debit(account(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK), grossMinor - providerFeeMinor)
                .debit(account(LedgerAccounts.PROVIDER_FEE_EXPENSE), providerFeeMinor)
                .credit(account(LedgerAccounts.WORKER_PAYABLE), grossMinor - platformFeeMinor)
                .credit(account(LedgerAccounts.PLATFORM_FEE_REVENUE), platformFeeMinor);
        return save(posting);
    }

    /**
     * Posts a full or partial refund.
     *
     * <p>The platform's commission is given back in proportion to the refunded
     * share; the provider's fee is <em>not</em> reversed, because Paystack
     * keeps it on a refunded charge and the books should say so.
     *
     * @param alreadyRefundedMinor how much of this payment had been refunded
     *                             <em>before</em> this refund — required, not
     *                             read off the payment, so the caller cannot
     *                             change the answer by updating the payment
     *                             first. See {@link #proportionalFeeShare}.
     */
    @Transactional
    public LedgerTransaction postRefund(Payment payment, String reference,
                                        long refundMinor, long alreadyRefundedMinor) {
        long platformFeeShare = proportionalFeeShare(payment, refundMinor, alreadyRefundedMinor);
        LedgerTransaction posting = new LedgerTransaction(reference, LedgerTransactionType.REFUND,
                payment.getCurrency(), "Refund of " + refundMinor + " on payment " + payment.getReference(),
                payment.getReference(), null)
                .debit(account(LedgerAccounts.WORKER_PAYABLE), refundMinor - platformFeeShare)
                .debit(account(LedgerAccounts.PLATFORM_FEE_REVENUE), platformFeeShare)
                .credit(account(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK), refundMinor);
        return save(posting);
    }

    /**
     * The share of the platform's commission attributable to one refund.
     *
     * <p>Computed as the difference between the commission owed on everything
     * refunded <em>including</em> this refund and the commission owed on
     * everything refunded before it. Written that way on purpose: the naive
     * version, {@code floor(fee * thisRefund / captured)}, is applied
     * independently to each instalment, so a charge refunded in three parts
     * loses up to two units of commission to flooring and leaves the worker's
     * payable account slightly negative once the payment is fully refunded.
     * The cumulative form telescopes — the shares sum to exactly
     * {@code floor(fee * captured / captured) = fee} no matter how the refund
     * is split — so a fully refunded payment returns the commission to the
     * unit and every affected account lands back on zero.
     *
     * <p>Clamped at {@code refundMinor} so a malformed capture amount could
     * never hand back more commission than the refund itself.
     */
    long proportionalFeeShare(Payment payment, long refundMinor, long alreadyRefundedMinor) {
        long captured = payment.getCapturedAmountMinor();
        if (captured <= 0) {
            return 0;
        }
        long fee = payment.getPlatformFeeMinor();
        long owedAfter = Math.floorDiv(Math.multiplyExact(fee, alreadyRefundedMinor + refundMinor), captured);
        long owedBefore = Math.floorDiv(Math.multiplyExact(fee, alreadyRefundedMinor), captured);
        return Math.min(owedAfter - owedBefore, refundMinor);
    }

    @Transactional
    public LedgerTransaction postPayout(Payout payout, String reference) {
        long amount = payout.getAmountMinor();
        long fee = payout.getFeeMinor();
        LedgerTransaction posting = new LedgerTransaction(reference, LedgerTransactionType.PAYOUT,
                payout.getCurrency(), "Payout " + payout.getReference() + " settled",
                null, payout.getReference())
                .debit(account(LedgerAccounts.WORKER_PAYABLE), amount)
                .debit(account(LedgerAccounts.PROVIDER_FEE_EXPENSE), fee)
                .credit(account(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK), amount + fee);
        return save(posting);
    }

    /**
     * Undoes a settled payout by posting its mirror image. The original
     * {@link LedgerTransactionType#PAYOUT} row is left exactly as it was —
     * the books then show both that the money went out and that it came back,
     * which is what happened.
     */
    @Transactional
    public LedgerTransaction postPayoutReversal(Payout payout, String reference) {
        long amount = payout.getAmountMinor();
        long fee = payout.getFeeMinor();
        LedgerTransaction posting = new LedgerTransaction(reference, LedgerTransactionType.PAYOUT_REVERSAL,
                payout.getCurrency(), "Payout " + payout.getReference() + " reversed by the provider",
                null, payout.getReference())
                .debit(account(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK), amount + fee)
                .credit(account(LedgerAccounts.WORKER_PAYABLE), amount)
                .credit(account(LedgerAccounts.PROVIDER_FEE_EXPENSE), fee);
        return save(posting);
    }

    private LedgerTransaction save(LedgerTransaction posting) {
        // Explicit, even though @PrePersist would catch it: this way the stack
        // trace points at the rule that built the bad posting rather than at
        // the flush that happened to notice.
        posting.validateBalanced();
        LedgerTransaction saved = transactions.save(posting);
        log.info("Ledger posting reference={} type={} currency={} amount={} entries={}",
                saved.getReference(), saved.getType(), saved.getCurrency(),
                saved.totalMinor(LedgerDirection.DEBIT), saved.getEntries().size());
        return saved;
    }

    private LedgerAccount account(String code) {
        return accounts.findByCode(code).orElseThrow(() -> new LedgerAccountNotFoundException(code));
    }

    // --- read side ---------------------------------------------------------

    /**
     * Sum of one account's entries on its own normal-balance side, minus the
     * other side — i.e. "how much is in this account", positive for a
     * normally-behaving account of any type.
     */
    @Transactional(readOnly = true)
    public long balanceOf(String accountCode, String currency) {
        LedgerAccount ledgerAccount = account(accountCode);
        LedgerDirection normal = ledgerAccount.getType().normalBalance();
        long onNormalSide = entries.totalByAccountAndDirection(accountCode, normal, currency);
        long onOtherSide = entries.totalByAccountAndDirection(accountCode, normal.opposite(), currency);
        return onNormalSide - onOtherSide;
    }

    /** @return the trial balance for one currency; {@code balanced()} is the invariant that must always hold. */
    @Transactional(readOnly = true)
    public TrialBalance trialBalance(String currency) {
        return new TrialBalance(currency,
                entries.totalByDirection(LedgerDirection.DEBIT, currency),
                entries.totalByDirection(LedgerDirection.CREDIT, currency));
    }

    /**
     * Every account's balance in one currency, keyed by account code and
     * expressed on the account's own normal side (see {@link #balanceOf}).
     * Iterates the chart of accounts rather than the entries, so an account
     * with no activity yet reads as zero instead of being absent.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> accountBalances(String currency) {
        java.util.Map<String, Long> balances = new java.util.LinkedHashMap<>();
        for (LedgerAccount ledgerAccount : LedgerAccounts.chartOfAccounts()) {
            balances.put(ledgerAccount.getCode(), balanceOf(ledgerAccount.getCode(), currency));
        }
        return balances;
    }

    /** Every currency the book holds entries in — what a full trial balance has to iterate. */
    @Transactional(readOnly = true)
    public List<String> currencies() {
        return entries.findDistinctCurrencies();
    }

    @Transactional(readOnly = true)
    public List<LedgerTransaction> postingsForPayment(String paymentReference) {
        return transactions.findWithEntriesByPaymentReference(paymentReference);
    }

    /**
     * @param debitsMinor  total of every debit line in this currency
     * @param creditsMinor total of every credit line in this currency
     */
    public record TrialBalance(String currency, long debitsMinor, long creditsMinor) {

        public boolean balanced() {
            return debitsMinor == creditsMinor;
        }

        /** Non-zero only if something has gone very wrong; useful in an alert message. */
        public long differenceMinor() {
            return debitsMinor - creditsMinor;
        }
    }
}
