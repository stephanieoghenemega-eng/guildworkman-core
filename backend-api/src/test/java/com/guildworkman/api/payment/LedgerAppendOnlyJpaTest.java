package com.guildworkman.api.payment;

import com.guildworkman.api.payment.model.LedgerTransaction;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.repository.LedgerEntryRepository;
import com.guildworkman.api.payment.repository.LedgerTransactionRepository;
import com.guildworkman.api.payment.repository.PaymentRepository;
import com.guildworkman.api.payment.service.LedgerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The append-only guard as JPA actually applies it, rather than as the guard
 * method behaves when called directly.
 *
 * <p>{@code LedgerPostingTest} calls {@code rejectUpdate()} itself, which
 * proves the method throws but not that Hibernate ever reaches it. Two
 * different questions matter in production and neither is answered by that:
 *
 * <ol>
 *   <li>Does an unconditional {@code @PreUpdate} misfire? A guard that throws
 *       whenever a posting merely sits in the persistence context would break
 *       every unrelated write in the same transaction — the webhook handler
 *       loads postings and saves a {@code Payment} in one unit of work, so a
 *       spurious trip would take out the capture path itself.</li>
 *   <li>If something does try to rewrite a posting, does the row survive? It
 *       does, and measuring <em>why</em> corrected an assumption: because every
 *       column is {@code updatable = false}, Hibernate emits no UPDATE and the
 *       {@code @PreUpdate} guard is never reached. The mapping is the defence
 *       that actually fires; the guard is a backstop for a column added later
 *       without the flag. The assertion is therefore on the durable row, which
 *       stays true whichever mechanism engages.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "payments.paystack.secret-key=sk_test_append_only",
        "payments.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000"
})
class LedgerAppendOnlyJpaTest {

    private static final String CURRENCY = "NGN";

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private LedgerTransactionRepository ledgerTransactions;
    @Autowired
    private LedgerEntryRepository ledgerEntries;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("delete from transactions");
        ledgerEntries.deleteAll();
        ledgerTransactions.deleteAll();
        payments.deleteAll();
    }

    private Payment capturedPayment() {
        Payment payment = new Payment("GWM-" + UUID.randomUUID().toString().replace("-", ""),
                1L, 2L, null, "client@example.com", 500_000L, CURRENCY);
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setCapturedAmountMinor(500_000L);
        payment.setPlatformFeeMinor(12_500L);
        payment.setCapturedAt(Instant.now());
        return payments.saveAndFlush(payment);
    }

    private LedgerTransaction aPosting() {
        Payment payment = capturedPayment();
        return ledgerService.postChargeCapture(payment, payment.getReference(), 500_000L, 0L, 12_500L);
    }

    @Test
    @Transactional
    void aPostingSittingInThePersistenceContextDoesNotBreakUnrelatedWrites() {
        LedgerTransaction posting = aPosting();
        entityManager.clear();

        // Exactly the shape of the capture path: a posting is attached and its
        // entries are walked, and an unrelated entity is written and flushed in
        // the same unit of work. If the guard misfired on a merely-attached
        // posting, this flush would throw and take the capture path with it.
        LedgerTransaction loaded = entityManager.find(LedgerTransaction.class, posting.getId());
        assertThat(loaded.getEntries()).isNotEmpty();

        Payment other = capturedPayment();
        other.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        payments.saveAndFlush(other);

        assertThat(payments.findByReference(other.getReference()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @Transactional
    void readingAPostingAndFlushingChangesNothing() {
        Long id = aPosting().getId();
        entityManager.clear();

        LedgerTransaction loaded = entityManager.find(LedgerTransaction.class, id);
        loaded.getEntries().size();
        // A flush with a posting attached and untouched must be a no-op.
        entityManager.flush();

        assertThat(entityManager.find(LedgerTransaction.class, id).getNarrative())
                .isEqualTo(loaded.getNarrative());
    }

    @Test
    void aRewrittenPostingNeverReachesTheDatabase() throws Exception {
        LedgerTransaction posting = aPosting();
        Long id = posting.getId();
        String originalNarrative = jdbcTemplate.queryForObject(
                "select narrative from ledger_transactions where id = ?", String.class, id);

        // No setter exists, which is the primary defence. Reflection is the
        // only way to stage the mutation a careless future caller would have
        // to write a setter to make.
        LedgerTransaction detached = ledgerTransactions.findById(id).orElseThrow();
        Field narrative = LedgerTransaction.class.getDeclaredField("narrative");
        narrative.setAccessible(true);
        narrative.set(detached, "rewritten history");

        // Measured, not assumed: this does NOT throw. Every column is
        // updatable = false, so Hibernate emits no UPDATE and @PreUpdate is
        // never reached. The mapping is what protects the row; the guard is a
        // backstop for the day someone adds a column and forgets the flag.
        // Asserting on the exception here would pin the wrong mechanism and
        // would start failing the moment the mapping changed for the better.
        ledgerTransactions.saveAndFlush(detached);

        assertThat(jdbcTemplate.queryForObject(
                "select narrative from ledger_transactions where id = ?", String.class, id))
                .isEqualTo(originalNarrative)
                .isNotEqualTo("rewritten history");
    }

    @Test
    void theJournalOnlyEverGrows() {
        long before = ledgerTransactions.count();
        aPosting();
        aPosting();
        assertThat(ledgerTransactions.count()).isEqualTo(before + 2);

        // Deleting is not exposed on the repository interface in any code path
        // that runs in production; the ops doc withholds DELETE at the grant
        // level. This just pins that nothing in the posting path removes rows.
        assertThat(ledgerEntries.count()).isGreaterThanOrEqualTo(4);
    }
}
