package com.guildworkman.api.payment.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
public class PaymentRequest {
    @JsonProperty
    private String email;
    @JsonProperty
    private BigDecimal amount;
}
