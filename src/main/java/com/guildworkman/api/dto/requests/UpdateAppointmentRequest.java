package com.guildworkman.api.dto.requests;

import com.guildworkman.api.data.constants.AppointmentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Setter
@Getter
public class UpdateAppointmentRequest {
    private Long clientId;
    private BigDecimal amount;
    private LocalDateTime startTime;
    private AppointmentStatus status;

}
