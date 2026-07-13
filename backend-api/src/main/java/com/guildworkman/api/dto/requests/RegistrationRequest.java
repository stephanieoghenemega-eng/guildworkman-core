package com.guildworkman.api.dto.requests;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@ToString
public class RegistrationRequest {
    private String fullName;
    private String email;
    private String password;

}
