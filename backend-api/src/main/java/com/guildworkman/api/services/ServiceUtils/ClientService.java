package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.data.models.Client;
import com.guildworkman.api.dto.requests.*;
import com.guildworkman.api.dto.responses.*;

import java.util.List;

public interface ClientService {

    ClientRegistrationResponse registerClient(RegistrationRequest registerRequest);


    BookAppointmentResponse bookAppointment(BookAppointmentRequest bookAppointmentRequest);

    CancelAppointmentResponse cancelAppointment(Long id, CancelAppointmentRequest cancelAppointmentRequest);

    UpdateAppointmentResponse updateAppointment(Long Id,UpdateAppointmentRequest request);

    DeleteAppointmentResponse deleteAppointment(Long id, DeleteAppointmentRequest request);

    List<ViewAllAppointmentsResponse> viewAllAppointment(Long id);

    Client findById(Long clientId);

    Long getNumberOfUsers();

    LoginResponse login(LoginRequest loginRequest);

    UpdateClientResponse updateClientProfile(UpdateClientRequest updateRequest);
}
