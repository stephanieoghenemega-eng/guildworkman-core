package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.DiscrepancyType;
import com.guildworkman.api.payment.model.ReconciliationDiscrepancy;
import com.guildworkman.api.payment.repository.ReconciliationDiscrepancyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Records a divergence for an operator to act on. The one place a finding is
 * written, so every caller gets the same deduplication and the same log line.
 *
 * <p><b>Deliberately not {@code @Transactional} itself.</b> The insert runs in
 * a nested transaction ({@link DiscrepancyInserter}) for two reasons: a repeat
 * finding raises a unique-key violation, and without isolation that would
 * poison the caller's transaction — rolling back the webhook processing or
 * reconciliation pass that was merely trying to note something down; and a
 * finding recorded about work that then fails is still worth keeping, because
 * the observation was real regardless of what happened next.
 *
 * <p>The catch has to be here, outside that nested transaction — see
 * {@link DiscrepancyInserter} for why catching it inside would turn a benign
 * duplicate into an {@code UnexpectedRollbackException}.
 *
 * <p>Logged at WARN with the reference and both states in the message, so a
 * log-based alert works before anyone builds a dashboard over the table.
 */
@Service
@RequiredArgsConstructor
public class DiscrepancyRecorder {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyRecorder.class);

    private final ReconciliationDiscrepancyRepository discrepancies;
    private final DiscrepancyInserter inserter;
    private final PaymentMetrics metrics;

    /**
     * @return the newly recorded finding, or empty if an identical one is
     *         already open — identical meaning same type, same reference and
     *         the same two states, so a genuinely changed divergence still
     *         opens a new row.
     */
    public Optional<ReconciliationDiscrepancy> record(DiscrepancyType type, String resourceReference,
                                                      String platformState, String providerState, String detail) {
        String dedupeKey = ReconciliationDiscrepancy.dedupeKey(type, resourceReference, platformState, providerState);
        if (discrepancies.existsByDedupeKey(dedupeKey)) {
            log.debug("Discrepancy {} for reference={} already recorded", type, resourceReference);
            return Optional.empty();
        }
        try {
            inserter.insert(new ReconciliationDiscrepancy(
                    type, resourceReference, platformState, providerState, detail));
        } catch (DataIntegrityViolationException ex) {
            // Lost the race to another sweep or a concurrent webhook. The
            // nested transaction rolled back on its own; the row exists either
            // way, which is all this method promises.
            log.debug("Discrepancy {} for reference={} was recorded concurrently", type, resourceReference);
            return Optional.empty();
        }
        metrics.discrepancyRecorded(type);
        Optional<ReconciliationDiscrepancy> recorded = discrepancies.findByDedupeKey(dedupeKey);
        // The id is in the message so an operator triaging this line can go
        // straight to /api/v1/payments/reconciliation/discrepancies without
        // having to re-derive the dedupe key by hand.
        log.warn("Payment reconciliation discrepancy id={} type={} reference={} platform={} provider={}: {}",
                recorded.map(ReconciliationDiscrepancy::getId).orElse(null),
                type, resourceReference, platformState, providerState, detail);
        return recorded;
    }
}
