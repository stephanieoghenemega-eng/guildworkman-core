package com.guildworkman.api.payment.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the {@code x-paystack-signature} header: an HMAC-SHA512 of the
 * request body, keyed with the account's secret key, hex-encoded.
 *
 * <p><b>The body must be the raw bytes.</b> Not a re-serialized DTO, not a
 * {@code String} that has been through a parse/print cycle. Jackson does not
 * promise to reproduce byte-for-byte what it read — key order, whitespace and
 * number formatting are all free to change — and any one of those differences
 * changes the MAC. The controller therefore takes {@code byte[]} and this
 * class runs before anything looks at the JSON, which is also what the issue
 * asks for: an unverified payload never reaches a parser.
 *
 * <p><b>An empty secret rejects everything.</b> The obvious alternative — skip
 * verification when no secret is configured, so local development is
 * convenient — is the same as publishing an unauthenticated endpoint that
 * mutates the ledger, and it fails open exactly in the deployment most likely
 * to have been misconfigured. Development and CI configure a test secret
 * instead.
 */
@Component
@RequiredArgsConstructor
public class PaystackSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(PaystackSignatureVerifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA512";

    public static final String SIGNATURE_HEADER = "x-paystack-signature";

    private final PaystackProperties properties;
    private final PaymentMetrics metrics;

    /**
     * @throws InvalidWebhookSignatureException if the header is missing, is not
     *         the hex encoding of the expected MAC, or no secret is configured.
     */
    public void verify(byte[] rawBody, String suppliedSignature) {
        String secret = properties.getSecretKey();
        if (secret == null || secret.isBlank()) {
            // ERROR, not WARN: in production this means every payment
            // notification is being dropped, which looks exactly like "we
            // stopped getting paid" a few hours later.
            log.error("Rejecting Paystack webhook: payments.paystack.secret-key is not configured, "
                    + "so no signature can be verified");
            metrics.signatureFailure(PaymentMetrics.SignatureFailure.NOT_CONFIGURED);
            throw new InvalidWebhookSignatureException("Webhook signature verification is not configured");
        }
        if (suppliedSignature == null || suppliedSignature.isBlank()) {
            metrics.signatureFailure(PaymentMetrics.SignatureFailure.MISSING_HEADER);
            throw new InvalidWebhookSignatureException("Missing " + SIGNATURE_HEADER + " header");
        }
        if (rawBody == null || rawBody.length == 0) {
            metrics.signatureFailure(PaymentMetrics.SignatureFailure.EMPTY_BODY);
            throw new InvalidWebhookSignatureException("Empty webhook body");
        }

        byte[] expected = hexMac(rawBody, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);

        // MessageDigest.isEqual is the constant-time comparison; String.equals
        // short-circuits on the first differing character, which over enough
        // attempts leaks how much of a forged prefix was correct.
        if (!MessageDigest.isEqual(expected, supplied)) {
            log.warn("Rejected Paystack webhook with an invalid signature ({} bytes of body)", rawBody.length);
            metrics.signatureFailure(PaymentMetrics.SignatureFailure.MISMATCH);
            throw new InvalidWebhookSignatureException("Invalid " + SIGNATURE_HEADER);
        }
    }

    /** Exposed for tests, which need to sign a body the way Paystack would. */
    public static String hexMac(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException ex) {
            // HmacSHA512 is required of every JRE, so this is unreachable
            // short of a broken security provider — but swallowing it would
            // turn a broken provider into "all signatures are invalid".
            throw new IllegalStateException("HMAC-SHA512 is unavailable in this JVM", ex);
        }
    }
}
