package com.guildworkman.api.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.guildworkman.api.data.constants.AppointmentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Setter
@Getter
public class BookAppointmentResponse {

    private LocalDateTime scheduleTime;
    private AppointmentStatus status;
    private String message;
}
