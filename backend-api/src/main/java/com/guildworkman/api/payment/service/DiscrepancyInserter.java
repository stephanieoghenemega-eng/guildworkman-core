package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.ReconciliationDiscrepancy;
import com.guildworkman.api.payment.repository.ReconciliationDiscrepancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated insert for a reconciliation finding, so a unique-key race on
 * {@code dedupe_key} aborts only this nested transaction. Mirrors
 * {@code EscrowOrchestrationInserter} and {@code ChainEventInserter}.
 *
 * <p><b>Why this is a separate bean rather than a try/catch in
 * {@link DiscrepancyRecorder}.</b> A flush failure marks its transaction
 * rollback-only. Catching the resulting
 * {@link org.springframework.dao.DataIntegrityViolationException} <em>inside</em>
 * the transaction it just poisoned does not rescue anything — the method
 * returns normally and Spring then fails the commit with
 * {@code UnexpectedRollbackException}, turning a benign duplicate into an
 * error. The catch has to live outside the transaction boundary, which means
 * the insert has to be its own bean.
 *
 * <p>The insert can also deadlock against nothing: it only ever adds a new
 * row, so Postgres resolves contention on the unique index by blocking the
 * second inserter until the first commits and then failing it with a
 * unique-violation.
 */
@Service
@RequiredArgsConstructor
public class DiscrepancyInserter {

    private final ReconciliationDiscrepancyRepository discrepancies;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationDiscrepancy insert(ReconciliationDiscrepancy discrepancy) {
        return discrepancies.saveAndFlush(discrepancy);
    }
}
