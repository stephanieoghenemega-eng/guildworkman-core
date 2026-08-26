package com.guildworkman.api.payment.api;

import com.guildworkman.api.payment.model.MinorUnits;
import com.guildworkman.api.payment.model.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A payment's current state.
 *
 * <p>{@code allowedNextStatuses} is included deliberately: the lifecycle is a
 * documented state machine, and a client that can see which transitions are
 * still possible does not have to hardcode a copy of it to know whether a
 * payment is still in play.
 *
 * @param amount         what the client was asked for
 * @param capturedAmount what was actually captured; zero until capture
 * @param refundedAmount running total refunded
 * @param platformFee    the platform's commission on the capture
 * @param providerFee    Paystack's processing fee on the capture
 */
public record PaymentStatusResponse(
        String reference,
        String status,
        Set<String> allowedNextStatuses,
        String currency,
        BigDecimal amount,
        BigDecimal capturedAmount,
        BigDecimal refundedAmount,
        BigDecimal platformFee,
        BigDecimal providerFee,
        Long clientId,
        Long skilledWorkerId,
        Long appointmentId,
        String failureReason,
        Instant createdAt,
        Instant capturedAt,
        Instant reconciledAt) {

    public static PaymentStatusResponse from(Payment payment) {
        String currency = payment.getCurrency();
        return new PaymentStatusResponse(
                payment.getReference(),
                payment.getStatus().name(),
                payment.getStatus().allowedNext().stream().map(Enum::name).collect(Collectors.toSet()),
                currency,
                MinorUnits.toMajor(payment.getAmountMinor(), currency),
                MinorUnits.toMajor(payment.getCapturedAmountMinor(), currency),
                MinorUnits.toMajor(payment.getRefundedAmountMinor(), currency),
                MinorUnits.toMajor(payment.getPlatformFeeMinor(), currency),
                MinorUnits.toMajor(payment.getProviderFeeMinor(), currency),
                payment.getClientId(),
                payment.getSkilledWorkerId(),
                payment.getAppointmentId(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getCapturedAt(),
                payment.getReconciledAt());
    }
}
