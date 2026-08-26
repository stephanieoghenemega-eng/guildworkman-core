package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByReference(String reference);

    boolean existsByReference(String reference);

    /**
     * Fetch-joins the lines and their accounts. The audit view renders account
     * codes, and {@code LedgerEntry.account} is {@code LAZY} — without the
     * join the mapping would run after the transaction closed and fail on a
     * detached proxy. Fetching here rather than making the association eager
     * keeps the posting path, which never reads account codes back, at one
     * query.
     */
    @Query("select distinct t from LedgerTransaction t "
            + "join fetch t.entries e join fetch e.account "
            + "where t.paymentReference = :paymentReference order by t.id")
    List<LedgerTransaction> findWithEntriesByPaymentReference(@Param("paymentReference") String paymentReference);

    List<LedgerTransaction> findByPaymentReferenceOrderByIdAsc(String paymentReference);

    List<LedgerTransaction> findByPayoutReferenceOrderByIdAsc(String payoutReference);
}
