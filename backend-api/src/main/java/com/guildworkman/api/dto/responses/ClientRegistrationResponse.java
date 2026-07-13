package com.guildworkman.api.dto.responses;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ClientRegistrationResponse {
    private Long clientId;
    private String message;
}
