package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByPaymentReference(String paymentReference);

    /**
     * Ordered by id rather than {@code transactionDate}: the date column is a
     * {@code LocalDate}, so several transactions on the same day would come
     * back in an arbitrary order, and "most recent first" would only be true
     * to the day.
     */
    List<Transaction> findByClientIdOrderByTransactionIdDesc(Long clientId, Pageable pageable);

    List<Transaction> findBySkilledWorkerIdOrderByTransactionIdDesc(Long skilledWorkerId, Pageable pageable);
}
