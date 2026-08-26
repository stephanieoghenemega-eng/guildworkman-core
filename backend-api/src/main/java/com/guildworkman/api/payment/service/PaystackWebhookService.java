package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.WebhookOutcome;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The webhook entry point, and the order the steps have to happen in:
 *
 * <ol>
 *   <li>verify the HMAC over the <em>raw</em> bytes — before parsing, so an
 *       unsigned payload never reaches a JSON parser;</li>
 *   <li>parse, which also derives the idempotency key from those same bytes;</li>
 *   <li>apply, in a single transaction that both claims the event and
 *       performs its effect.</li>
 * </ol>
 *
 * <p>Deliberately not {@code @Transactional}. A unique-key collision on the
 * event id is how two concurrent deliveries of the same event are resolved,
 * and it has to be caught <em>outside</em> the transaction it aborted —
 * catching it inside would leave a rollback-only transaction with nothing
 * useful left to do.
 */
@Service
@RequiredArgsConstructor
public class PaystackWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookService.class);

    private final PaystackSignatureVerifier verifier;
    private final PaystackEventParser parser;
    private final PaystackWebhookProcessor processor;
    private final PaymentMetrics metrics;

    /**
     * @param rawBody          the exact bytes of the request body
     * @param suppliedSignature the {@code x-paystack-signature} header
     * @throws InvalidWebhookSignatureException  if the signature is missing or wrong
     * @throws MalformedWebhookPayloadException  if the (verified) body is not a readable envelope
     */
    public WebhookOutcome handle(byte[] rawBody, String suppliedSignature) {
        verifier.verify(rawBody, suppliedSignature);
        PaystackEvent event = parser.parse(rawBody);
        try {
            WebhookOutcome outcome = processor.process(event);
            metrics.webhookEvent(event.type(), outcome);
            return outcome;
        } catch (DataIntegrityViolationException ex) {
            // Another delivery of this same event committed while this one was
            // in flight. Its transaction rolled back having applied nothing,
            // so the effect happened exactly once — by the other thread.
            log.info("Paystack event key={} was applied concurrently by another delivery", event.eventKey());
            metrics.webhookEvent(event.type(), WebhookOutcome.DUPLICATE);
            return WebhookOutcome.DUPLICATE;
        }
    }
}
