package com.guildworkman.api.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider boundary, driven against a real HTTP server rather than a
 * mocked client, so the request this code actually puts on the wire is part
 * of what's asserted.
 *
 * <p>The case that matters most for reconciliation is the distinction between
 * "Paystack says it has no such transaction" (an answer, and a finding) and
 * "Paystack didn't answer" (not an answer, and not a finding).
 */
class PaystackClientTest {

    private MockWebServer server;
    private PaystackClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        PaystackProperties properties = new PaystackProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setSecretKey("sk_test_secret");

        client = new PaystackClient(new OkHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setBody(body)
                .addHeader("Content-Type", "application/json");
    }

    @Test
    void initializeSendsTheReferenceAmountAndMetadataAndReadsBackTheCheckoutUrl() throws InterruptedException {
        server.enqueue(json(200, "{\"status\":true,\"message\":\"Authorization URL created\",\"data\":{"
                + "\"authorization_url\":\"https://checkout.paystack.com/abc\","
                + "\"access_code\":\"abc\",\"reference\":\"GWM-1\"}}"));

        PaystackInitialization initialization = client.initializeTransaction(
                "GWM-1", "client@example.com", 150_000L, "NGN", Map.of("skilledWorkerId", 31L));

        assertThat(initialization.authorizationUrl()).isEqualTo("https://checkout.paystack.com/abc");
        assertThat(initialization.accessCode()).isEqualTo("abc");
        assertThat(initialization.reference()).isEqualTo("GWM-1");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/transaction/initialize");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk_test_secret");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"reference\":\"GWM-1\"")
                .contains("\"amount\":\"150000\"")
                .contains("\"currency\":\"NGN\"")
                .contains("\"skilledWorkerId\":\"31\"");
    }

    @Test
    void verifyParsesTheProvidersViewOfATransaction() throws InterruptedException {
        server.enqueue(json(200, "{\"status\":true,\"message\":\"Verification successful\",\"data\":{"
                + "\"id\":302961,\"status\":\"success\",\"reference\":\"GWM-1\","
                + "\"amount\":150000,\"fees\":2250,\"currency\":\"NGN\"}}"));

        Optional<PaystackTransaction> result = client.verifyTransaction("GWM-1");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("success");
        assertThat(result.get().amountMinor()).isEqualTo(150_000L);
        assertThat(result.get().feesMinor()).isEqualTo(2_250L);
        assertThat(result.get().providerId()).isEqualTo(302961L);

        assertThat(server.takeRequest().getPath()).isEqualTo("/transaction/verify/GWM-1");
    }

    @Test
    void aReferenceWithUrlSyntaxIsEncodedIntoThePathRatherThanReshapingIt() throws InterruptedException {
        server.enqueue(json(404, "{\"status\":false,\"message\":\"Transaction reference not found\"}"));

        client.verifyTransaction("GWM-1/../transaction/charge?x=1");

        // The whole thing is one percent-encoded path segment; nothing has been
        // able to steer the request at a different endpoint.
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).startsWith("/transaction/verify/");
        assertThat(recorded.getPath()).doesNotContain("charge?x=1");
    }

    @Test
    void aMissingTransactionIsAnEmptyResultNotAFailure() {
        server.enqueue(json(404, "{\"status\":false,\"message\":\"Transaction reference not found\"}"));

        assertThat(client.verifyTransaction("GWM-unknown")).isEmpty();
    }

    @Test
    void anOutageThrowsRatherThanLookingLikeAMissingTransaction() {
        // The distinction reconciliation depends on: if a 500 came back as an
        // empty Optional, one Paystack outage would file a MISSING_PROVIDER_RECORD
        // finding against every payment in the sweep.
        server.enqueue(json(500, "{\"status\":false,\"message\":\"Internal server error\"}"));

        assertThatThrownBy(() -> client.verifyTransaction("GWM-1"))
                .isInstanceOf(PaystackClientException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void aBusinessLevelFailureThrows() {
        server.enqueue(json(200, "{\"status\":false,\"message\":\"Invalid key\"}"));

        assertThatThrownBy(() -> client.verifyTransaction("GWM-1"))
                .isInstanceOf(PaystackClientException.class)
                .hasMessageContaining("Invalid key");
    }

    @Test
    void aResponseWithoutADataObjectThrows() {
        server.enqueue(json(200, "{\"status\":true,\"message\":\"ok\"}"));

        assertThatThrownBy(() -> client.verifyTransaction("GWM-1"))
                .isInstanceOf(PaystackClientException.class)
                .hasMessageContaining("data");
    }

    @Test
    void anOversizedErrorBodyIsTruncatedBeforeItReachesTheExceptionMessage() {
        String huge = "x".repeat(5_000);
        server.enqueue(json(500, "{\"status\":false,\"message\":\"" + huge + "\"}"));

        assertThatThrownBy(() -> client.verifyTransaction("GWM-1"))
                .isInstanceOf(PaystackClientException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(1_000));
    }
}
