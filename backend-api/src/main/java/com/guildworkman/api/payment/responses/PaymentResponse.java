package com.guildworkman.api.payment.responses;

import com.guildworkman.api.data.models.Data;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class PaymentResponse {
    private String status;
    private String message;
    private Data data;


}
