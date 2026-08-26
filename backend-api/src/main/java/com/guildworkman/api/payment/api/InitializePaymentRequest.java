package com.guildworkman.api.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Starts a payment and asks Paystack for a checkout URL.
 *
 * @param clientId        the client paying
 * @param skilledWorkerId the worker being paid; the ledger credits their payable balance on capture
 * @param appointmentId   optional link to the appointment this pays for
 * @param customerEmail   where Paystack sends the receipt; required by their initialize endpoint
 * @param amount          the price in major units, e.g. {@code 7500.00}. Capped at four integer
 *                        digits beyond a realistic marketplace price is not the concern here —
 *                        the {@code Digits} bound exists so the value still converts to minor
 *                        units inside a {@code long} with room to spare
 * @param currency        ISO-4217 code; defaults to {@code payments.default-currency} when omitted
 */
public record InitializePaymentRequest(
        @NotNull Long clientId,
        @NotNull Long skilledWorkerId,
        Long appointmentId,
        @NotNull @Email @Size(max = 255) String customerEmail,
        // Two decimal places is the widest any currency Paystack settles in
        // uses; a third would be silently dropped or would fail conversion, so
        // it is rejected at the boundary with a message that says why.
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter ISO-4217 code") String currency) {
}
