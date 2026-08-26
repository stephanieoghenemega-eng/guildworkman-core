package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.data.models.Transaction;
import com.guildworkman.api.payment.model.Payment;

import java.util.Optional;

/**
 * Maintains the {@code Transaction} projection of the payments ledger.
 *
 * <p>{@code Transaction} is a <b>view</b>, not a second set of books. It
 * exists because the rest of the application (and its clients) already speak
 * in terms of it, and because "show me this client's transactions" should not
 * require replaying journal entries. Everything it holds is recomputed from
 * the {@code Payment} aggregate, which is itself derived from the append-only
 * ledger — so if the two ever disagree, the ledger is right by definition and
 * the projection can be rebuilt.
 */
public interface TransactionService {

    /**
     * Creates or refreshes the projected row for {@code payment}. Idempotent:
     * calling it twice for the same payment updates one row rather than
     * creating a second, which matters because it is invoked from the webhook
     * path on every state change.
     */
    Transaction projectFromPayment(Payment payment);

    Optional<Transaction> findByPaymentReference(String paymentReference);
}
