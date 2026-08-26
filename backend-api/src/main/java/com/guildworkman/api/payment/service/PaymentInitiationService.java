package com.guildworkman.api.payment.service;

import com.guildworkman.api.exceptions.GuildWorkmanException;
import com.guildworkman.api.payment.model.MinorUnits;
import com.guildworkman.api.payment.model.Payment;
import com.guildworkman.api.payment.model.PaymentStatus;
import com.guildworkman.api.payment.repository.PaymentRepository;
import com.guildworkman.api.services.ServiceUtils.TransactionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a payment and hands the client a Paystack checkout URL.
 *
 * <p><b>The reference is generated here, not by Paystack.</b> Letting the
 * provider assign it would mean the platform has no record of the payment
 * until the initialize call returns — and if that call times out after
 * Paystack created the transaction, there would be a live charge with no
 * local row and nothing to correlate the eventual webhook against. Generating
 * it first means the row always exists before the provider knows anything,
 * and a failed initialize leaves a payment the reconciliation sweep can ask
 * about by name.
 *
 * <p><b>No method here is {@code @Transactional} across the HTTP call.</b>
 * Each repository write is its own short transaction, so the Paystack
 * round-trip never holds a database connection open — the same reasoning
 * {@code SorobanRpcClient}'s timeout handling is built on, applied at the
 * transaction level.
 */
@Service
@RequiredArgsConstructor
public class PaymentInitiationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiationService.class);

    private static final String REFERENCE_PREFIX = "GWM-";

    private final PaymentRepository payments;
    private final PaystackClient paystackClient;
    private final PaymentProperties properties;
    private final TransactionService transactionService;

    /**
     * @param amountMajor the price as a person would write it (e.g. {@code 7500.00});
     *                    converted to minor units once, here, so nothing downstream
     *                    has to decide what scale it is in
     * @throws GuildWorkmanException if the amount is not positive or carries more
     *                               decimal places than the currency can express.
     *                               Bean validation on the request already
     *                               rejects a negative or three-decimal amount;
     *                               this catches the case validation cannot
     *                               know about, a two-decimal amount in a
     *                               zero-decimal currency, and reports it as
     *                               the client error it is rather than a 500
     */
    public Payment initiate(Long clientId, Long skilledWorkerId, Long appointmentId,
                            String customerEmail, BigDecimal amountMajor, String currency) {
        String resolvedCurrency = (currency == null || currency.isBlank())
                ? properties.getDefaultCurrency()
                : currency.toUpperCase(java.util.Locale.ROOT);
        long amountMinor;
        try {
            amountMinor = MinorUnits.toMinor(amountMajor, resolvedCurrency);
        } catch (IllegalArgumentException ex) {
            throw new GuildWorkmanException(ex.getMessage());
        }
        if (amountMinor <= 0) {
            throw new GuildWorkmanException("Payment amount must be greater than zero");
        }

        String reference = REFERENCE_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Payment payment = new Payment(reference, clientId, skilledWorkerId, appointmentId,
                customerEmail, amountMinor, resolvedCurrency);
        payments.saveAndFlush(payment);
        log.info("Payment reference={} created for client={} worker={} amount={} {}",
                reference, clientId, skilledWorkerId, amountMinor, resolvedCurrency);

        try {
            PaystackInitialization initialization = paystackClient.initializeTransaction(
                    reference, customerEmail, amountMinor, resolvedCurrency, correlationMetadata(payment));
            payment.setAuthorizationUrl(initialization.authorizationUrl());
            payment.setAccessCode(initialization.accessCode());
        } catch (PaystackClientException ex) {
            // Terminal on purpose. The alternative — leaving it INITIATED —
            // means the reconciliation sweep asks Paystack about a transaction
            // that was never created, forever, and reports a missing provider
            // record that is entirely expected.
            log.warn("Paystack initialization failed for reference={}: {}", reference, ex.getMessage());
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Could not create the transaction at Paystack: " + ex.getMessage());
            savePaymentAndProjection(payment);
            throw ex;
        }

        savePaymentAndProjection(payment);
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment get(String reference) {
        return payments.findByReference(reference).orElseThrow(() -> new PaymentNotFoundException(reference));
    }

    private void savePaymentAndProjection(Payment payment) {
        payments.save(payment);
        transactionService.projectFromPayment(payment);
    }

    /**
     * Sent to Paystack at initialization and echoed back on every webhook for
     * this transaction. The reference alone is enough to find the payment, so
     * these are redundancy rather than a dependency — but they are what makes
     * a transfer or a manually-created charge traceable when the reference is
     * all that is otherwise known.
     */
    private Map<String, Object> correlationMetadata(Payment payment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("paymentReference", payment.getReference());
        metadata.put("clientId", payment.getClientId());
        metadata.put("skilledWorkerId", payment.getSkilledWorkerId());
        if (payment.getAppointmentId() != null) {
            metadata.put("appointmentId", payment.getAppointmentId());
        }
        return metadata;
    }
}
