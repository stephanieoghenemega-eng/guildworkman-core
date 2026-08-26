package com.guildworkman.api.payment.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two invariants the journal enforces on itself: a posting balances, and
 * a posted entry never changes.
 *
 * <p>No Spring context here on purpose — these are properties of the
 * aggregate, and they should hold before anything is persisted.
 */
class LedgerPostingTest {

    private static final LedgerAccount RECEIVABLE =
            new LedgerAccount(LedgerAccounts.PROVIDER_RECEIVABLE_PAYSTACK, LedgerAccountType.ASSET, "test");
    private static final LedgerAccount PAYABLE =
            new LedgerAccount(LedgerAccounts.WORKER_PAYABLE, LedgerAccountType.LIABILITY, "test");

    private static LedgerTransaction posting() {
        return new LedgerTransaction("ref-1", LedgerTransactionType.CHARGE_CAPTURE, "NGN",
                "test posting", "GWM-1", null);
    }

    @Test
    void aBalancedPostingIsAccepted() {
        LedgerTransaction posting = posting().debit(RECEIVABLE, 1_000).credit(PAYABLE, 1_000);

        assertThatCode(posting::validateBalanced).doesNotThrowAnyException();
        assertThat(posting.totalMinor(LedgerDirection.DEBIT)).isEqualTo(1_000);
        assertThat(posting.totalMinor(LedgerDirection.CREDIT)).isEqualTo(1_000);
    }

    @Test
    void aPostingWhoseSidesDisagreeIsRefused() {
        LedgerTransaction posting = posting().debit(RECEIVABLE, 1_000).credit(PAYABLE, 999);

        assertThatThrownBy(posting::validateBalanced)
                .isInstanceOf(UnbalancedLedgerTransactionException.class)
                .hasMessageContaining("1000")
                .hasMessageContaining("999");
    }

    @Test
    void aSingleSidedPostingIsRefused() {
        LedgerTransaction posting = posting().debit(RECEIVABLE, 1_000);

        assertThatThrownBy(posting::validateBalanced)
                .isInstanceOf(UnbalancedLedgerTransactionException.class)
                .hasMessageContaining("at least one debit and one credit");
    }

    @Test
    void aZeroAmountLineIsDroppedRatherThanWritten() {
        // Fee splits are computed arithmetically; a fee-free charge shouldn't
        // force every posting rule to branch around a zero.
        LedgerTransaction posting = posting()
                .debit(RECEIVABLE, 1_000)
                .debit(PAYABLE, 0)
                .credit(PAYABLE, 1_000);

        assertThat(posting.getEntries()).hasSize(2);
        assertThatCode(posting::validateBalanced).doesNotThrowAnyException();
    }

    @Test
    void aNegativeAmountIsRefusedAtTheLine() {
        // Otherwise "debit -500" is an undeclared credit, and a pair of them
        // would balance while describing the opposite of what happened.
        assertThatThrownBy(() -> posting().debit(RECEIVABLE, -500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void balanceCheckRunsOnPersistSoNoCodePathCanSkipIt() {
        // validateBalanced is the @PrePersist callback; calling it is what
        // Hibernate does on the way to the database.
        LedgerTransaction posting = posting().debit(RECEIVABLE, 5).credit(PAYABLE, 6);

        assertThatThrownBy(posting::validateBalanced)
                .isInstanceOf(UnbalancedLedgerTransactionException.class);
    }

    @Test
    void aPostedJournalEntryRefusesToBeModified() {
        assertThatThrownBy(() -> posting().rejectUpdate())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void aPostedLineRefusesToBeModified() {
        LedgerTransaction posting = posting().debit(RECEIVABLE, 10).credit(PAYABLE, 10);

        assertThatThrownBy(() -> posting.getEntries().get(0).rejectUpdate())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void theJournalExposesNoSettersToMutateItThrough() {
        // The @PreUpdate guard is a backstop. The primary defence is that
        // there is no in-process way to dirty one of these in the first place,
        // and that is easy to regress by adding @Setter to the class.
        assertThat(setterNames(LedgerTransaction.class)).isEmpty();
        assertThat(setterNames(LedgerEntry.class)).isEmpty();
    }

    @Test
    void theEntriesCollectionCannotBeEditedThroughItsGetter() {
        LedgerTransaction posting = posting().debit(RECEIVABLE, 10).credit(PAYABLE, 10);

        assertThatThrownBy(() -> posting.getEntries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static java.util.List<String> setterNames(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();
    }
}
