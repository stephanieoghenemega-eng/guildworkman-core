package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.dto.requests.*;
import com.guildworkman.api.dto.responses.*;
import com.guildworkman.api.data.models.SkilledWorker;

import java.util.List;

public interface SkilledWorkerService {
    Long getNumberOfUsers();

    SkilledWorkerRegistrationResponse registerSkilledWorker(RegistrationRequest registrationRequest);

    AddSkillResponse addSkill(AddSkillRequest addSkillRequest);

    SkilledWorker findById(Long skilledWorkerId);
    SkilledWorker findSkillByFullName(String  skilledWorkerFullName);

    AcceptAppointmentResponse acceptAppointment(AcceptAppointmentRequest acceptAppointmentRequest);

    LoginResponse login(LoginRequest loginRequest);

    UpdateSkilledWorkerResponse updateSkilledWorkerProfile(UpdateSkilledWorkerRequest request);

    List<SkilledWorker> findWorkersNear(double lat, double lon, double radius);
}
