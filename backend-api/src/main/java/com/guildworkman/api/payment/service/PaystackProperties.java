package com.guildworkman.api.payment.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds {@code payments.paystack.*}: how to reach Paystack, and the secret
 * that both signs outbound API calls and verifies inbound webhooks.
 *
 * <p>The secret defaults from the same {@code PAYSTACK_SECRET_KEY} environment
 * variable the pre-existing {@code paystack.secret.key} property reads, so
 * there is still exactly one place an operator sets it. The properties are
 * kept separate rather than reusing {@code AppConfig}'s {@code @Value} fields
 * because {@code paystack.secret.key} is a three-segment name that does not
 * relax-bind onto a {@code secretKey} field, and renaming a property other
 * code already reads is a change that belongs on its own.
 */
@Component
@ConfigurationProperties(prefix = "payments.paystack")
@Getter
@Setter
public class PaystackProperties {

    /** API root; every path this service calls is resolved against it. */
    private String baseUrl = "https://api.paystack.co";

    /**
     * Paystack secret key. Empty by default so local dev and CI boot without
     * credentials — {@link PaystackSignatureVerifier} treats an empty secret
     * as "reject every webhook" rather than "accept every webhook", so an
     * unconfigured deployment fails closed.
     */
    private String secretKey = "";

    /** Per-call HTTP timeout for outbound Paystack requests. */
    private Duration requestTimeout = Duration.ofSeconds(10);
}
