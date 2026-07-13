package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.models.Address;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SkilledWorkerRegistrationResponse {
    private Long skilledWorkerId;
    private String message;

}
