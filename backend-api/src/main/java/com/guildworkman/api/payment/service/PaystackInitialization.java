package com.guildworkman.api.payment.service;

/**
 * The provider's response to {@code POST /transaction/initialize}: where to
 * send the client to pay.
 *
 * @param authorizationUrl Paystack-hosted checkout URL
 * @param accessCode       code for Paystack's inline/popup checkout
 * @param reference        the reference the transaction was created under —
 *                         echoed back, and expected to equal the one sent
 */
public record PaystackInitialization(String authorizationUrl, String accessCode, String reference) {
}
