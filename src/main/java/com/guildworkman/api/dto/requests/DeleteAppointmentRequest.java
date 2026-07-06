package com.guildworkman.api.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DeleteAppointmentRequest {
    @JsonProperty("appointment_Id")
    private Long id;


}
