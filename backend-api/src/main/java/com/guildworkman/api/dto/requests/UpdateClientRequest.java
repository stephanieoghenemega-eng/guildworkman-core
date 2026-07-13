package com.guildworkman.api.dto.requests;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UpdateClientRequest {
    private Long clientId;
    private String username;
    private String phoneNumber;
    private String password;
    private String houseNumber;
    private String street;
    private String area;

}
