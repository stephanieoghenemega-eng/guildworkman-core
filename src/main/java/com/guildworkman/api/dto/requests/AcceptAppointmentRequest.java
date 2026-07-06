package com.guildworkman.api.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.guildworkman.api.data.constants.AppointmentStatus;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AcceptAppointmentRequest {
    private Long appointmentId;
    @JsonProperty("skilled_worker")
    private Long Id;
    private AppointmentStatus status;
}
