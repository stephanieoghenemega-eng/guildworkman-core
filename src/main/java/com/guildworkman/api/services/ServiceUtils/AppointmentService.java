package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.dto.requests.AcceptAppointmentRequest;
import com.guildworkman.api.dto.requests.BookAppointmentRequest;
import com.guildworkman.api.dto.requests.UpdateAppointmentRequest;
import com.guildworkman.api.dto.responses.AcceptAppointmentResponse;
import com.guildworkman.api.dto.responses.UpdateAppointmentResponse;
import com.guildworkman.api.dto.responses.ViewAllAppointmentsResponse;
import com.guildworkman.api.data.models.Appointment;

import java.util.List;
import java.util.Optional;

public interface AppointmentService {
    Appointment bookAppointment(BookAppointmentRequest bookAppointmentRequest);
    void cancelAppointment(Long id);
    UpdateAppointmentResponse updateAppointment(Long Id,UpdateAppointmentRequest request);
    void deleteAppointment(Long id);
    List<ViewAllAppointmentsResponse> viewAllAppointment();

    Optional<Appointment> findAppointmentById(Long id);

    void save(Appointment appointment);

    AcceptAppointmentResponse acceptAppointment(AcceptAppointmentRequest request);

    Long getAppointments();
}
