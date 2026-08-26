package com.guildworkman.api.payment.api;

import com.guildworkman.api.handler.ProblemDetails;
import com.guildworkman.api.payment.repository.LedgerTransactionRepository;
import com.guildworkman.api.payment.repository.ProcessedWebhookEventRepository;
import com.guildworkman.api.payment.service.PaystackClient;
import com.guildworkman.api.payment.service.PaystackSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract, and specifically the security shape of it: one
 * unauthenticated endpoint that authenticates its payload, and an otherwise
 * token-gated surface with the reconciliation views behind ADMIN.
 *
 * <p>The webhook being public is the whole reason this test class exists. It
 * is the one route that can move money without a bearer token, so it is worth
 * asserting directly that reaching it with no token gets past security, that
 * reaching it with a wrong signature does not get past verification, and that
 * the neighbouring payment routes did not inherit the exemption.
 */
@SpringBootTest(properties = {
        "payments.paystack.secret-key=" + PaymentEndpointSecurityTest.SECRET,
        "payments.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000"
})
@AutoConfigureMockMvc
class PaymentEndpointSecurityTest {

    static final String SECRET = "sk_test_http_contract";
    private static final String WEBHOOK_PATH = "/api/v1/webhooks/paystack";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProcessedWebhookEventRepository processedEvents;
    @Autowired
    private LedgerTransactionRepository ledgerTransactions;

    @MockBean
    private PaystackClient paystackClient;

    private static byte[] body(String reference) {
        return ("{\"event\":\"charge.success\",\"data\":{\"id\":" + System.nanoTime()
                + ",\"status\":\"success\",\"reference\":\"" + reference
                + "\",\"amount\":1000,\"fees\":15,\"currency\":\"NGN\"}}").getBytes(StandardCharsets.UTF_8);
    }

    // --- the public webhook -------------------------------------------------

    @Test
    void aSignedWebhookIsAcceptedWithNoBearerToken() {
        byte[] payload = body("GWM-http-unknown");
        String signature = PaystackSignatureVerifier.hexMac(payload, SECRET);

        // The reference is unknown, so the event is REJECTED at the domain
        // level — but it still answers 200, which is the point: the endpoint
        // was reachable, the signature verified, and Paystack is told to stop
        // retrying an event that will never become applicable.
        assertThat(perform(payload, signature)).isEqualTo(200);
    }

    @Test
    void aForgedSignatureIsAnUnauthorizedProblemResponse() throws Exception {
        byte[] payload = body("GWM-http-forged");
        long eventsBefore = processedEvents.count();
        long postingsBefore = ledgerTransactions.count();

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PaystackSignatureVerifier.SIGNATURE_HEADER,
                                PaystackSignatureVerifier.hexMac(payload, "sk_test_not_the_secret"))
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Webhook signature rejected"))
                // Deliberately incurious: a prober learns the signature was
                // wrong and nothing about why or how close it was.
                .andExpect(jsonPath("$.detail").value("The webhook signature could not be verified"));

        assertThat(processedEvents.count()).isEqualTo(eventsBefore);
        assertThat(ledgerTransactions.count()).isEqualTo(postingsBefore);
    }

    @Test
    void aWebhookWithNoSignatureHeaderIsRejected() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("GWM-http-unsigned")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE));
    }

    @Test
    void aSignedButUnreadableBodyIsABadRequest() throws Exception {
        byte[] payload = "{\"not\":\"a paystack envelope\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PaystackSignatureVerifier.SIGNATURE_HEADER,
                                PaystackSignatureVerifier.hexMac(payload, SECRET))
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Malformed webhook payload"));
    }

    // --- everything else stays token-gated ----------------------------------

    @Test
    void thePaymentRoutesDidNotInheritTheWebhookExemption() throws Exception {
        mockMvc.perform(get("/api/v1/payments/GWM-anything")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payments/GWM-anything/ledger")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payments/reconciliation/discrepancies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void anUnknownPaymentReferenceIsANotFoundProblemResponse() throws Exception {
        mockMvc.perform(get("/api/v1/payments/GWM-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    @Test
    @WithMockUser
    void anInvalidInitializeRequestReportsEveryOffendingField() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"not-an-email\",\"amount\":\"0.00\",\"currency\":\"NAIRA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.errors.clientId").exists())
                .andExpect(jsonPath("$.errors.skilledWorkerId").exists())
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.amount").exists())
                .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    @WithMockUser(roles = "USER")
    void theReconciliationViewsAreAdminOnly() throws Exception {
        // A trial balance is every naira the platform holds, and the
        // discrepancy list is a list of the places money might be wrong.
        mockMvc.perform(get("/api/v1/payments/reconciliation/discrepancies"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payments/reconciliation/trial-balance"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminCanReadTheTrialBalance() throws Exception {
        mockMvc.perform(get("/api/v1/payments/reconciliation/trial-balance").param("currency", "NGN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.accountBalances.WORKER_PAYABLE").exists());
    }

    private int perform(byte[] payload, String signature) {
        try {
            return mockMvc.perform(post(WEBHOOK_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(PaystackSignatureVerifier.SIGNATURE_HEADER, signature)
                            .content(payload))
                    .andReturn().getResponse().getStatus();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
