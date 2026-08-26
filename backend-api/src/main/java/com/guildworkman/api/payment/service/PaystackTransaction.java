package com.guildworkman.api.payment.service;

/**
 * The provider's view of one transaction, as returned by
 * {@code GET /transaction/verify/:reference}. This is the "provider side" of
 * every reconciliation comparison.
 *
 * @param reference  the transaction reference that was looked up
 * @param providerId Paystack's numeric id, null if the response omitted it
 * @param status     Paystack's status string: {@code success}, {@code failed},
 *                   {@code abandoned}, {@code ongoing}, {@code pending}, {@code reversed}
 * @param amountMinor amount in minor units
 * @param feesMinor   Paystack's fee in minor units, 0 if not reported
 * @param currency    ISO-4217 code
 */
public record PaystackTransaction(String reference, Long providerId, String status,
                                  long amountMinor, long feesMinor, String currency) {
}
