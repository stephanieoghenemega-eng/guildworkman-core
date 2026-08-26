package com.guildworkman.api.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns verified raw webhook bytes into a {@link PaystackEvent}, and — the
 * part worth reading — decides what makes one delivery the "same event" as
 * another.
 *
 * <p><b>Paystack has no delivery id.</b> The envelope is {@code {event, data}}
 * and nothing else; there is no {@code X-Event-Id} header and no {@code id}
 * on the envelope. So the idempotency key has to be derived, and the
 * derivation is the whole correctness argument for "a retry never credits
 * twice". Two rules, in order:
 *
 * <ol>
 *   <li><b>{@code event-type:data.id}</b> when the payload carries a resource
 *       id. This is the good case and covers every event type this service
 *       acts on: {@code data.id} is Paystack's own primary key for the
 *       charge, refund or transfer, and it is stable across retries of the
 *       same delivery. Including the event type matters because one transfer
 *       id legitimately produces {@code transfer.success} and later
 *       {@code transfer.reversed}, and those must not collide.</li>
 *   <li><b>{@code event-type:sha256:<digest of the raw body>}</b> otherwise.
 *       A retry of a delivery re-sends the identical body, so identical bytes
 *       dedupe. The limitation is real and worth stating: if Paystack ever
 *       varied a timestamp between retries of an id-less event, this would
 *       let the retry through. That is not a silent double-credit —
 *       {@link com.guildworkman.api.payment.model.PaymentStatus} refuses the
 *       second transition and it is flagged as a discrepancy — but it is why
 *       the digest is the fallback and not the primary rule.</li>
 * </ol>
 *
 * <p>The digest is over the raw bytes, the same bytes the signature was
 * checked against, so it cannot be influenced by how Jackson chose to parse
 * them.
 *
 * <p><b>Why not a canonicalized digest?</b> Normalizing the JSON first — sorting
 * keys, dropping whitespace, or excluding volatile fields like a timestamp —
 * would make the fallback survive a provider that re-serializes between
 * retries. It is deliberately not done, for two reasons. Canonicalizing means
 * hashing something other than what was signed, so the dedupe key would no
 * longer be bound to the bytes the HMAC authenticated; and excluding fields
 * requires deciding which ones are volatile, which is a guess about a payload
 * schema this service does not own — guess wrong and two genuinely different
 * events collapse onto one key, which drops a real event silently. The failure
 * mode of the raw digest is a duplicate that gets refused loudly by the state
 * machine; the failure mode of a bad canonicalization is a lost event. The
 * first is the safer way to be wrong.
 */
@Component
@RequiredArgsConstructor
public class PaystackEventParser {

    private final ObjectMapper objectMapper;

    public PaystackEvent parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException ex) {
            throw new MalformedWebhookPayloadException("Webhook body is not valid JSON", ex);
        } catch (java.io.IOException ex) {
            throw new MalformedWebhookPayloadException("Webhook body could not be read", ex);
        }
        if (root == null || !root.hasNonNull("event")) {
            throw new MalformedWebhookPayloadException("Webhook body has no 'event' field");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            throw new MalformedWebhookPayloadException("Webhook body has no 'data' object");
        }

        String type = root.get("event").asText();
        return new PaystackEvent(type, data, eventKey(type, data, rawBody));
    }

    static String eventKey(String type, JsonNode data, byte[] rawBody) {
        if (data.hasNonNull("id")) {
            return type + ":" + data.get("id").asText();
        }
        return type + ":sha256:" + sha256Hex(rawBody);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }
}
