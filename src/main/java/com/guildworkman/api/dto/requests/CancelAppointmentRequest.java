package com.guildworkman.api.dto.requests;


import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.constants.Category;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CancelAppointmentRequest {
    private Long id;
    private Category category;
    private AppointmentStatus status;

}
