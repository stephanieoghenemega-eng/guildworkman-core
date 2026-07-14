package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.dto.requests.AcceptAppointmentRequest;
import com.guildworkman.api.dto.requests.BookAppointmentRequest;
import com.guildworkman.api.dto.requests.UpdateAppointmentRequest;
import com.guildworkman.api.dto.responses.AcceptAppointmentResponse;
import com.guildworkman.api.dto.responses.UpdateAppointmentResponse;
import com.guildworkman.api.dto.responses.ViewAllAppointmentsResponse;
import com.guildworkman.api.exceptions.AppointmentNotFoundException;
import com.guildworkman.api.data.models.Appointment;
import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.repository.AppointmentRepository;
import com.guildworkman.api.data.repository.SkilledWorkerRepository;
import com.guildworkman.api.exceptions.GuildWorkmanException;
import com.guildworkman.api.exceptions.UserNotFoundException;
import com.guildworkman.api.services.ServiceUtils.AppointmentService;
import lombok.AllArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {
    private final AppointmentRepository appointmentRepository;
    private  final ModelMapper modelMapper;
    private final SkilledWorkerRepository skilledWorkerRepository;


//    @Autowired
//    public void setClientService(ClientService clientService) {
//        this.clientService = clientService;
//    }

    @Override
    public Appointment bookAppointment(BookAppointmentRequest bookAppointmentRequest) {
        Appointment appointment = modelMapper.map(bookAppointmentRequest, Appointment.class);
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        // Record WHICH worker was booked. Set explicitly rather than relying on
        // ModelMapper, which can implicitly build a transient SkilledWorker from
        // skilledWorkerId — save that and Hibernate blows up. Resolve the managed
        // entity (or clear it) before persisting.
        appointment.setSkilledWorker(resolveSkilledWorker(bookAppointmentRequest.getSkilledWorkerId()));
        appointment.setAmount(bookAppointmentRequest.getAmount());

        appointmentRepository.save(appointment);

        return appointment;

    }

    private SkilledWorker resolveSkilledWorker(Long skilledWorkerId) {
        // Optional: callers that don't send a worker still book successfully,
        // they just produce an appointment with no worker attached.
        if (skilledWorkerId == null) {
            return null;
        }
        return skilledWorkerRepository.findById(skilledWorkerId)
                .orElseThrow(() -> new UserNotFoundException(
                        "Skilled worker not found: " + skilledWorkerId));
    }



    @Override
    public void cancelAppointment(Long id) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found"));
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointmentRepository.save(appointment);
//        CancelAppointmentResponse response = new CancelAppointmentResponse();
//        modelMapper.map(response, Appointment.class);
//        response.setMessage("Appointment cancelled successfully");
//        return response;

    }

    @Override
    public UpdateAppointmentResponse updateAppointment(Long Id,UpdateAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(Id)
                .orElseThrow(()-> new AppointmentNotFoundException("No appointment found"));

        // Apply what the caller actually asked for. This used to map the request
        // into a THROWAWAY Appointment (the result was discarded) and then hardcode
        // UPDATED — so ACCEPTED/DECLINED never reached the database and accepting or
        // declining a job did nothing at all.
        appointment.setStatus(request.getStatus() != null
                ? request.getStatus()
                : AppointmentStatus.UPDATED);
        if (request.getAmount() != null) {
            appointment.setAmount(request.getAmount());
        }
        if (request.getStartTime() != null) {
            appointment.setScheduleTime(request.getStartTime());
        }
        appointmentRepository.save(appointment);

        UpdateAppointmentResponse response = new UpdateAppointmentResponse();
        response.setId(appointment.getId());
        response.setMessage("Appointment Updated");
        return response;
    }

    @Override
    public void deleteAppointment(Long id) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(()-> new AppointmentNotFoundException("No appointment found"));
        appointmentRepository.delete(appointment);

    }

    @Override
    public List<ViewAllAppointmentsResponse> viewAllAppointment() {
        var  appointments = appointmentRepository.findAll();

        return List.of(modelMapper
                .map(appointments, ViewAllAppointmentsResponse[].class));
    }

    @Override
    public Optional<Appointment> findAppointmentById(Long id) {
        return appointmentRepository.findById(id);

    }




    @Override
    public void save(Appointment appointment) {
        appointmentRepository.save(appointment);

    }

    @Override
    public AcceptAppointmentResponse acceptAppointment(AcceptAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(request.getAppointmentId())
                .orElseThrow(()-> new GuildWorkmanException("appointment not found"));
//        appointment.setClient(clientService.findById(request.getClientId()));
//        appointment.setSkilledWorker(request.getSkilledWorker());
        appointment.setStatus(AppointmentStatus.ACCEPTED);
        appointmentRepository.save(appointment);
        AcceptAppointmentResponse response = new AcceptAppointmentResponse();
        response.setStatus(request.getStatus());
        response.setClientId(request.getId());
        response.setAppointmentId(request.getAppointmentId());
        return response;
    }

    @Override
    public Long getAppointments() {
        return appointmentRepository.count();
    }


}
