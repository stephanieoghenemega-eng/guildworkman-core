package com.guildworkman.api.payment.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Builder
public class PayStackData {
    @JsonProperty("authorizationUrl")
    private String authorizationUrl;
    @JsonProperty("access_code")
    private String accessCode;
    private String reference;

}
