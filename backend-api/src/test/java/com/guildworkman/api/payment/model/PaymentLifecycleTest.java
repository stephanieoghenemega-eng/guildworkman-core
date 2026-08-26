package com.guildworkman.api.payment.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition tables, pinned. The two things worth guarding against are a
 * transition being quietly added (which would let an out-of-order event
 * through) and a terminal state stopping being terminal.
 */
class PaymentLifecycleTest {

    // --- charges -----------------------------------------------------------

    @Test
    void aChargeCanRunFromInitiatedToCaptured() {
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.PENDING)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.SUCCEEDED)).isTrue();
    }

    @Test
    void aRefundCanOnlyFollowACapture() {
        assertThat(PaymentStatus.SUCCEEDED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        assertThat(PaymentStatus.SUCCEEDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();

        // The out-of-order case the whole design turns on: a refund event that
        // overtakes the charge it refunds.
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isFalse();
    }

    @Test
    void severalPartialRefundsAreAllowedButASecondSuccessIsNot() {
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
        // A redelivered charge.success never reaches the state machine (the
        // event log stops it). A *distinct* second success event is the
        // provider telling us something unreconcilable, so it is refused.
        assertThat(PaymentStatus.SUCCEEDED.canTransitionTo(PaymentStatus.SUCCEEDED)).isFalse();
    }

    @Test
    void aFailedChargeCannotLaterSucceed() {
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.SUCCEEDED)).isFalse();
        assertThat(PaymentStatus.ABANDONED.canTransitionTo(PaymentStatus.SUCCEEDED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"FAILED", "ABANDONED", "REFUNDED", "REVERSED"})
    void terminalChargeStatesHaveNoWayOut(PaymentStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedNext()).isEmpty();
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void everyChargeStateHasATransitionTableEntry(PaymentStatus status) {
        // Guards the one failure mode a hand-maintained EnumMap has: a new
        // constant added without a row, which would NPE on first use.
        assertThat(status.allowedNext()).isNotNull();
    }

    @Test
    void onlyStatesThatHaveTouchedMoneyCountAsCaptured() {
        assertThat(PaymentStatus.SUCCEEDED.isCaptured()).isTrue();
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.isCaptured()).isTrue();
        assertThat(PaymentStatus.REFUNDED.isCaptured()).isTrue();
        assertThat(PaymentStatus.REVERSED.isCaptured()).isTrue();

        assertThat(PaymentStatus.INITIATED.isCaptured()).isFalse();
        assertThat(PaymentStatus.PENDING.isCaptured()).isFalse();
        assertThat(PaymentStatus.FAILED.isCaptured()).isFalse();
        assertThat(PaymentStatus.ABANDONED.isCaptured()).isFalse();
    }

    // --- payouts -----------------------------------------------------------

    @Test
    void aPayoutSettlesOrFails() {
        assertThat(PayoutStatus.PENDING.canTransitionTo(PayoutStatus.PAID)).isTrue();
        assertThat(PayoutStatus.PENDING.canTransitionTo(PayoutStatus.FAILED)).isTrue();
    }

    @Test
    void onlyASettledPayoutCanBeReversed() {
        assertThat(PayoutStatus.PAID.canTransitionTo(PayoutStatus.REVERSED)).isTrue();
        // transfer.reversed arriving before transfer.success: refused, because
        // there is no payout entry in the books to reverse.
        assertThat(PayoutStatus.PENDING.canTransitionTo(PayoutStatus.REVERSED)).isFalse();
        assertThat(PayoutStatus.FAILED.canTransitionTo(PayoutStatus.REVERSED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PayoutStatus.class, names = {"FAILED", "REVERSED"})
    void terminalPayoutStatesHaveNoWayOut(PayoutStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (PayoutStatus next : PayoutStatus.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(PayoutStatus.class)
    void everyPayoutStateHasATransitionTableEntry(PayoutStatus status) {
        assertThat(status.allowedNext()).isNotNull();
    }
}
