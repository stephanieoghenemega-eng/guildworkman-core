package com.skilledservice.ClientService.services.implmentations;

import com.skilledservice.ClientService.dto.requests.AcceptAppointmentRequest;
import com.skilledservice.ClientService.dto.requests.BookAppointmentRequest;
import com.skilledservice.ClientService.dto.requests.UpdateAppointmentRequest;
import com.skilledservice.ClientService.dto.responses.AcceptAppointmentResponse;
import com.skilledservice.ClientService.dto.responses.UpdateAppointmentResponse;
import com.skilledservice.ClientService.dto.responses.ViewAllAppointmentsResponse;
import com.skilledservice.ClientService.exceptions.AppointmentNotFoundException;
import com.skilledservice.ClientService.data.models.Appointment;
import com.skilledservice.ClientService.data.constants.AppointmentStatus;
import com.skilledservice.ClientService.data.repository.AppointmentRepository;
import com.skilledservice.ClientService.exceptions.GuildWorkmanException;
import com.skilledservice.ClientService.services.ServiceUtils.AppointmentService;
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


//    @Autowired
//    public void setClientService(ClientService clientService) {
//        this.clientService = clientService;
//    }

    @Override
    public Appointment bookAppointment(BookAppointmentRequest bookAppointmentRequest) {
        Appointment appointment = modelMapper.map(bookAppointmentRequest, Appointment.class);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointmentRepository.save(appointment);

        return appointment;

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

        modelMapper.map(request, Appointment.class);
        appointment.setStatus(AppointmentStatus.UPDATED);
        appointmentRepository.save(appointment);

        UpdateAppointmentResponse response = new UpdateAppointmentResponse();
        response .setId(appointment.getId());
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
