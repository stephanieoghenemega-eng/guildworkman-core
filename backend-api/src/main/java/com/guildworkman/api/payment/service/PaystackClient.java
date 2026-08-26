package com.guildworkman.api.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound calls to Paystack: create a transaction, and ask what the provider
 * thinks a transaction's state is.
 *
 * <p>Modelled on {@code SorobanRpcClient}, including its timeout handling —
 * the shared {@code OkHttpClient} bean is rebuilt with this integration's own
 * call/connect/read/write budget so a hung Paystack endpoint cannot pin a
 * scheduler thread (and, with it, the reconciliation sweep) indefinitely.
 *
 * <p><b>404 is data, not an error.</b> {@code verifyTransaction} returns an
 * empty {@link Optional} when Paystack has no record of a reference, because
 * that is a meaningful answer — it is exactly the
 * {@code MISSING_PROVIDER_RECORD} case reconciliation exists to report. Every
 * other failure throws, so an outage can never be mistaken for "the provider
 * has never heard of any of these payments".
 *
 * <p>Response bodies are truncated before being logged or put in an exception
 * message: a Paystack error body echoes back the request, and this is the one
 * place a payload becomes a string that might reach application logs.
 */
@Component
public class PaystackClient {

    private static final Logger log = LoggerFactory.getLogger(PaystackClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_LOGGED_BODY_LENGTH = 500;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PaystackProperties properties;

    public PaystackClient(OkHttpClient httpClient, ObjectMapper objectMapper, PaystackProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        Duration timeout = properties.getRequestTimeout();
        this.httpClient = httpClient.newBuilder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    /**
     * Creates a transaction at Paystack under a reference this service chose.
     *
     * @param metadata correlation values echoed back on every webhook for this
     *                 transaction — the only channel Paystack offers for
     *                 tying its events back to platform ids
     */
    public PaystackInitialization initializeTransaction(String reference, String email, long amountMinor,
                                                        String currency, Map<String, Object> metadata) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reference", reference);
        body.put("email", email);
        // Paystack's initialize endpoint takes the amount in minor units as a
        // string; sending a number works today but the API documents a string.
        body.put("amount", String.valueOf(amountMinor));
        body.put("currency", currency);
        ObjectNode metadataNode = body.putObject("metadata");
        metadata.forEach((key, value) -> metadataNode.put(key, String.valueOf(value)));

        Request request = new Request.Builder()
                .url(resolve("transaction", "initialize"))
                .header("Authorization", "Bearer " + properties.getSecretKey())
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        JsonNode data = execute(request, "initializeTransaction", reference)
                .orElseThrow(() -> new PaystackClientException(
                        "Paystack did not create transaction reference=" + reference));
        return new PaystackInitialization(
                text(data, "authorization_url"),
                text(data, "access_code"),
                Optional.ofNullable(text(data, "reference")).orElse(reference));
    }

    /** @return the provider's view of the transaction, or empty if it has no record of the reference. */
    public Optional<PaystackTransaction> verifyTransaction(String reference) {
        Request request = new Request.Builder()
                .url(resolve("transaction", "verify", reference))
                .header("Authorization", "Bearer " + properties.getSecretKey())
                .get()
                .build();

        return execute(request, "verifyTransaction", reference).map(data -> new PaystackTransaction(
                Optional.ofNullable(text(data, "reference")).orElse(reference),
                data.hasNonNull("id") ? data.get("id").asLong() : null,
                text(data, "status"),
                data.hasNonNull("amount") ? data.get("amount").asLong() : 0L,
                data.hasNonNull("fees") ? data.get("fees").asLong() : 0L,
                Optional.ofNullable(text(data, "currency")).orElse("NGN")));
    }

    /**
     * @return the {@code data} object of a successful response, or empty when
     *         Paystack answered 404 (no such resource).
     */
    private Optional<JsonNode> execute(Request request, String operation, String reference) {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (response.code() == 404) {
                log.info("Paystack {} reference={} returned 404 (no provider record)", operation, reference);
                return Optional.empty();
            }
            if (!response.isSuccessful()) {
                throw new PaystackClientException("Paystack " + operation + " reference=" + reference
                        + " HTTP " + response.code() + ": " + truncate(responseBody));
            }
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.path("status").asBoolean(false)) {
                throw new PaystackClientException("Paystack " + operation + " reference=" + reference
                        + " reported failure: " + truncate(root.path("message").asText("(no message)")));
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) {
                throw new PaystackClientException("Paystack " + operation + " reference=" + reference
                        + " response has no 'data' object");
            }
            return Optional.of(data);
        } catch (IOException ex) {
            // Includes the call/connect/read timeouts configured above.
            throw new PaystackClientException("Paystack " + operation + " reference=" + reference
                    + " call failed: " + ex.getMessage(), ex);
        }
    }

    private HttpUrl resolve(String... segments) {
        HttpUrl base = HttpUrl.parse(properties.getBaseUrl());
        if (base == null) {
            throw new PaystackClientException("payments.paystack.base-url is not a valid URL: "
                    + properties.getBaseUrl());
        }
        HttpUrl.Builder builder = base.newBuilder();
        for (String segment : segments) {
            // addPathSegment percent-encodes, so a reference containing a slash
            // or a query character can't reshape the URL it's interpolated into.
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_LOGGED_BODY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOGGED_BODY_LENGTH) + "…(truncated, " + value.length() + " chars total)";
    }
}
