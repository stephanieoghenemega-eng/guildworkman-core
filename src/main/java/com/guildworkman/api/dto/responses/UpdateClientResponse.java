package com.guildworkman.api.dto.responses;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UpdateClientResponse {
    private Long clientId;
    private String username;
    private String phone;
    private String password;
    private String houseNumber;
    private String street;
    private String area;
}
