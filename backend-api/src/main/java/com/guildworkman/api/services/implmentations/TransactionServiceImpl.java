package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.constants.TransactionStatus;
import com.guildworkman.api.data.models.Transaction;
import com.guildworkman.api.data.repository.TransactionRepository;
import com.guildworkman.api.payment.model.MinorUnits;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.services.ServiceUtils.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Projects {@code Payment} state onto the pre-existing {@code Transaction}
 * view. See {@link TransactionService} for why this is a projection.
 *
 * <p>Runs inside the caller's transaction (the webhook processor's), so the
 * projection and the ledger posting it reflects commit together — there is no
 * window in which the journal says one thing and the view another.
 */
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactions;

    @Override
    @Transactional
    public Transaction projectFromPayment(Payment payment) {
        Transaction transaction = transactions.findByPaymentReference(payment.getReference())
                .orElseGet(Transaction::new);

        transaction.setPaymentReference(payment.getReference());
        transaction.setClientId(payment.getClientId());
        transaction.setSkilledWorkerId(payment.getSkilledWorkerId());
        // The captured amount once there is one, otherwise what was asked for:
        // an INITIATED or FAILED payment has captured nothing, and showing a
        // zero there would read as "this cost nothing" rather than "this
        // hasn't been paid".
        long amountMinor = payment.getStatus().isCaptured()
                ? payment.getCapturedAmountMinor()
                : payment.getAmountMinor();
        transaction.setAmount(MinorUnits.toMajor(amountMinor, payment.getCurrency()));
        transaction.setTransactionStatus(project(payment.getStatus()));
        transaction.setTransactionDate(transactionDate(payment));
        return transactions.save(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transaction> findByPaymentReference(String paymentReference) {
        return transactions.findByPaymentReference(paymentReference);
    }

    /**
     * {@code RECEIVED} is not produced here: it describes funds reaching a
     * skilled worker, which is the payout leg, and a payout is not tied to a
     * single charge (one transfer can settle many). Projecting it from a
     * charge would put a status on this row that the row cannot substantiate.
     */
    private static TransactionStatus project(PaymentStatus status) {
        switch (status) {
            case SUCCEEDED:
                return TransactionStatus.PAID;
            case PARTIALLY_REFUNDED:
            case REFUNDED:
                return TransactionStatus.REFUNDED;
            case REVERSED:
                return TransactionStatus.REVERSED;
            case FAILED:
            case ABANDONED:
                return TransactionStatus.FAILED;
            case INITIATED:
            case PENDING:
            default:
                return TransactionStatus.PENDING;
        }
    }

    /** Capture date once captured, creation date before that. */
    private static LocalDate transactionDate(Payment payment) {
        return (payment.getCapturedAt() != null ? payment.getCapturedAt() : payment.getCreatedAt())
                .atZone(ZoneOffset.UTC).toLocalDate();
    }
}
