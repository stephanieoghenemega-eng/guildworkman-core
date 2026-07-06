package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.models.Address;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UpdateSkilledWorkerRequest {
    private Long skilledWorkerId;
    private String fullName;
    private String username;
    private String email;
    private String phoneNumber;
    private String password;
    private String houseNumber;
    private String street;
    private String area;
}
