package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByReference(String reference);

    List<Payment> findByClientIdOrderByCreatedAtDesc(Long clientId, Pageable pageable);

    List<Payment> findBySkilledWorkerIdOrderByCreatedAtDesc(Long skilledWorkerId, Pageable pageable);

    /**
     * Reconciliation candidates: payments in one of the given states that were
     * created before {@code createdBefore}. The age filter is what stops the
     * sweep from chasing a payment the client is still sitting on the checkout
     * page for — see {@code PaymentProperties#reconciliationGrace}.
     */
    List<Payment> findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
            Collection<PaymentStatus> statuses, Instant createdBefore, Pageable pageable);

    long countByStatus(PaymentStatus status);
}
