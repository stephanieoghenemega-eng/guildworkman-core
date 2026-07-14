package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.constants.Category;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One appointment in a client's list.
 *
 * <p>{@code id} is what the client passes back to
 * {@code cancelAppointment} / {@code updateAppointment} / {@code deleteAppointment}
 * (they take {@code ?appointmentId=}), so it must be present — without it the
 * management flow is unreachable.
 *
 * <p>{@code scheduleTime} is serialised as ISO-8601 (e.g. {@code 2026-07-13T11:00:00});
 * clients format it for display.
 */
@Setter
@Getter
public class ViewAllAppointmentsResponse {
    private Long id;
    private AppointmentStatus status;
    private Category category;
    private LocalDateTime scheduleTime;
    private BigDecimal amount;
    private AppointmentWorkerResponse worker;
}
