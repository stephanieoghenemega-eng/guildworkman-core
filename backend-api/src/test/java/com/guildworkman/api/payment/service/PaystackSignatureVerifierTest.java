package com.guildworkman.api.payment.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate every webhook has to pass. These cases are the ones an attacker
 * would actually try — no signature, a plausible-looking wrong one, a
 * truncated one, a right signature over different bytes — plus the
 * misconfiguration case, where the interesting question is which way the
 * endpoint fails.
 */
class PaystackSignatureVerifierTest {

    private static final String SECRET = "sk_test_a1b2c3d4e5f6";
    private static final byte[] BODY =
            "{\"event\":\"charge.success\",\"data\":{\"id\":42,\"reference\":\"GWM-1\"}}"
                    .getBytes(StandardCharsets.UTF_8);

    private PaystackProperties properties;
    private PaystackSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new PaystackProperties();
        properties.setSecretKey(SECRET);
        verifier = new PaystackSignatureVerifier(properties, new PaymentMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void acceptsASignatureComputedTheWayPaystackComputesIt() {
        String signature = PaystackSignatureVerifier.hexMac(BODY, SECRET);

        assertThatCode(() -> verifier.verify(BODY, signature)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnUppercaseHexSignature() {
        // Nothing promises the header's case; normalising it is cheaper than
        // rejecting a legitimate delivery over it.
        String signature = PaystackSignatureVerifier.hexMac(BODY, SECRET).toUpperCase(java.util.Locale.ROOT);

        assertThatCode(() -> verifier.verify(BODY, signature)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAForgedSignature() {
        String forged = PaystackSignatureVerifier.hexMac(BODY, "sk_test_attackers_guess");

        assertThatThrownBy(() -> verifier.verify(BODY, forged))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsAMissingSignature() {
        assertThatThrownBy(() -> verifier.verify(BODY, null))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessageContaining(PaystackSignatureVerifier.SIGNATURE_HEADER);
    }

    @Test
    void rejectsABlankSignature() {
        assertThatThrownBy(() -> verifier.verify(BODY, "   "))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsATruncatedButOtherwiseCorrectSignature() {
        String valid = PaystackSignatureVerifier.hexMac(BODY, SECRET);

        assertThatThrownBy(() -> verifier.verify(BODY, valid.substring(0, valid.length() - 1)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsAValidSignatureOverDifferentBytes() {
        // The replay-with-edits case: a real signature lifted from one delivery
        // and pinned to a body that pays somebody else.
        String signatureForOriginal = PaystackSignatureVerifier.hexMac(BODY, SECRET);
        byte[] tampered = "{\"event\":\"charge.success\",\"data\":{\"id\":42,\"reference\":\"GWM-2\"}}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(tampered, signatureForOriginal))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void rejectsAnEmptyBody() {
        assertThatThrownBy(() -> verifier.verify(new byte[0], PaystackSignatureVerifier.hexMac(BODY, SECRET)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void failsClosedWhenNoSecretIsConfigured() {
        // The point of the test: an unconfigured deployment rejects everything
        // rather than accepting everything. The tempting alternative — skip
        // verification when there is no secret, so local development is
        // convenient — is an unauthenticated endpoint that moves money, and it
        // fails open in exactly the deployment most likely to be misconfigured.
        properties.setSecretKey("");
        String otherwiseValid = PaystackSignatureVerifier.hexMac(BODY, SECRET);

        assertThatThrownBy(() -> verifier.verify(BODY, otherwiseValid))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void failsClosedWhenTheSecretIsBlankRatherThanAbsent() {
        properties.setSecretKey("   ");

        assertThatThrownBy(() -> verifier.verify(BODY, PaystackSignatureVerifier.hexMac(BODY, SECRET)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }
}
