package com.guildworkman.api.payment.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@Builder
@NoArgsConstructor
public class ResponseBodyDetails<T> {

    @JsonProperty("status")
    private String status;
    @JsonProperty("message")
    private String message;
    @JsonProperty("data")
    private T data;

    public ResponseBodyDetails(String status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;

    }

}
