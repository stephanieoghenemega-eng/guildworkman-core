package com.guildworkman.api.dto.responses;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class LoginResponse {
    private String jwtToken;
    private String message;
    private Long userId;

}
