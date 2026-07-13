package com.guildworkman.api.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UpdateAppointmentResponse {
   @JsonProperty("appointment_id")
    private Long id;
   private String message;
   private String status;
}
