package com.guildworkman.api.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The idempotency key is derived, not supplied — Paystack sends no delivery
 * id — so what counts as "the same event" is decided here. These tests pin
 * that decision, because loosening it by accident is how a retry becomes a
 * second credit.
 */
class PaystackEventParserTest {

    private final PaystackEventParser parser = new PaystackEventParser(new ObjectMapper());

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keysOnEventTypeAndResourceIdWhenThePayloadHasOne() {
        PaystackEvent event = parser.parse(bytes(
                "{\"event\":\"charge.success\",\"data\":{\"id\":302961,\"reference\":\"GWM-1\"}}"));

        assertThat(event.eventKey()).isEqualTo("charge.success:302961");
        assertThat(event.type()).isEqualTo("charge.success");
        assertThat(event.reference()).isEqualTo("GWM-1");
    }

    @Test
    void aRetryOfTheSameDeliveryProducesTheSameKey() {
        String json = "{\"event\":\"charge.success\",\"data\":{\"id\":302961,\"reference\":\"GWM-1\"}}";

        assertThat(parser.parse(bytes(json)).eventKey())
                .isEqualTo(parser.parse(bytes(json)).eventKey());
    }

    @Test
    void oneTransferIdUnderTwoEventTypesProducesTwoKeys() {
        // A transfer legitimately succeeds and is later reversed. Keying on the
        // id alone would make the reversal look like a retry of the success and
        // silently drop it.
        String success = "{\"event\":\"transfer.success\",\"data\":{\"id\":77,\"reference\":\"TRF-1\"}}";
        String reversed = "{\"event\":\"transfer.reversed\",\"data\":{\"id\":77,\"reference\":\"TRF-1\"}}";

        assertThat(parser.parse(bytes(success)).eventKey())
                .isNotEqualTo(parser.parse(bytes(reversed)).eventKey());
    }

    @Test
    void fallsBackToADigestOfTheRawBodyWhenThereIsNoResourceId() {
        String json = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"GWM-1\",\"amount\":1000}}";

        PaystackEvent first = parser.parse(bytes(json));
        PaystackEvent second = parser.parse(bytes(json));

        assertThat(first.eventKey()).startsWith("charge.success:sha256:");
        assertThat(first.eventKey()).isEqualTo(second.eventKey());
    }

    @Test
    void differentBodiesWithoutIdsProduceDifferentKeys() {
        PaystackEvent one = parser.parse(bytes(
                "{\"event\":\"charge.success\",\"data\":{\"reference\":\"GWM-1\"}}"));
        PaystackEvent two = parser.parse(bytes(
                "{\"event\":\"charge.success\",\"data\":{\"reference\":\"GWM-2\"}}"));

        assertThat(one.eventKey()).isNotEqualTo(two.eventKey());
    }

    @Test
    void readsARefundsSubjectFromTransactionReference() {
        PaystackEvent event = parser.parse(bytes(
                "{\"event\":\"refund.processed\",\"data\":{\"id\":9,\"amount\":500,"
                        + "\"transaction_reference\":\"GWM-1\",\"refund_reference\":\"RFD-1\"}}"));

        assertThat(event.transactionReference()).isEqualTo("GWM-1");
        assertThat(event.amountMinor()).isEqualTo(500);
    }

    @Test
    void identifiesATransferByTransferCodeWhenItCarriesNoReference() {
        PaystackEvent event = parser.parse(bytes(
                "{\"event\":\"transfer.success\",\"data\":{\"id\":5,\"transfer_code\":\"TRF_abc\"}}"));

        assertThat(event.reference()).isEqualTo("TRF_abc");
    }

    @Test
    void readsMetadataWhetherPaystackEchoesItAsANumberOrAString() {
        PaystackEvent numeric = parser.parse(bytes(
                "{\"event\":\"transfer.success\",\"data\":{\"id\":5,\"metadata\":{\"skilledWorkerId\":31}}}"));
        PaystackEvent stringly = parser.parse(bytes(
                "{\"event\":\"transfer.success\",\"data\":{\"id\":5,\"metadata\":{\"skilledWorkerId\":\"31\"}}}"));

        assertThat(numeric.metadataLong("skilledWorkerId")).isEqualTo(31L);
        assertThat(stringly.metadataLong("skilledWorkerId")).isEqualTo(31L);
    }

    @Test
    void returnsNullForMetadataThatIsNotANumber() {
        PaystackEvent event = parser.parse(bytes(
                "{\"event\":\"transfer.success\",\"data\":{\"id\":5,\"metadata\":{\"skilledWorkerId\":\"n/a\"}}}"));

        assertThat(event.metadataLong("skilledWorkerId")).isNull();
        assertThat(event.metadataLong("absent")).isNull();
    }

    @Test
    void rejectsAnEnvelopeWithoutAnEventField() {
        assertThatThrownBy(() -> parser.parse(bytes("{\"data\":{\"id\":1}}")))
                .isInstanceOf(MalformedWebhookPayloadException.class)
                .hasMessageContaining("event");
    }

    @Test
    void rejectsAnEnvelopeWithoutADataObject() {
        assertThatThrownBy(() -> parser.parse(bytes("{\"event\":\"charge.success\"}")))
                .isInstanceOf(MalformedWebhookPayloadException.class)
                .hasMessageContaining("data");
    }

    @Test
    void rejectsBodyThatIsNotJson() {
        assertThatThrownBy(() -> parser.parse(bytes("not json at all")))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }
}
