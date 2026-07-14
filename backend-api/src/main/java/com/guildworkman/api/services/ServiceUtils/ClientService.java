package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.data.models.Client;
import com.guildworkman.api.dto.requests.*;
import com.guildworkman.api.dto.responses.*;

import java.util.List;

public interface ClientService {

    ClientRegistrationResponse registerClient(RegistrationRequest registerRequest);


    BookAppointmentResponse bookAppointment(BookAppointmentRequest bookAppointmentRequest);

    // The appointment id is all these need. They previously also took a request
    // DTO carrying a *client* id, which no caller sent (and which the cancel
    // endpoint didn't even bind as a body) — so every call failed on a null id.
    CancelAppointmentResponse cancelAppointment(Long appointmentId);

    UpdateAppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request);

    DeleteAppointmentResponse deleteAppointment(Long appointmentId);

    List<ViewAllAppointmentsResponse> viewAllAppointment(Long id);

    Client findById(Long clientId);

    Long getNumberOfUsers();

    LoginResponse login(LoginRequest loginRequest);

    UpdateClientResponse updateClientProfile(UpdateClientRequest updateRequest);
}
