package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.constants.AppointmentStatus;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AcceptAppointmentResponse {
    private Long clientId;
    private Long appointmentId;
    private AppointmentStatus status;
}
