package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.constants.Category;
import lombok.Getter;
import lombok.Setter;

/**
 * Summary of the skilled worker attached to an appointment — just enough for a
 * client to recognise who they booked, without leaking the worker's contact
 * details or credentials.
 */
@Setter
@Getter
public class AppointmentWorkerResponse {
    private Long id;
    private String fullName;
    private Category category;
}
