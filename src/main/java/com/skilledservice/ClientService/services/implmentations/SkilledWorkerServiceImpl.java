package com.skilledservice.ClientService.services.implmentations;

import com.skilledservice.ClientService.data.models.Address;
import com.skilledservice.ClientService.data.models.Appointment;
import com.skilledservice.ClientService.data.repository.AddressRepository;
import com.skilledservice.ClientService.data.repository.SkillRepository;
import com.skilledservice.ClientService.dto.requests.*;
import com.skilledservice.ClientService.dto.responses.*;
import com.skilledservice.ClientService.exceptions.InvalidEmailFoundException;
import com.skilledservice.ClientService.exceptions.InvalidPasswordException;
import com.skilledservice.ClientService.exceptions.GuildWorkmanException;
import com.skilledservice.ClientService.data.models.SkilledWorker;
import com.skilledservice.ClientService.data.repository.SkilledWorkerRepository;
import com.skilledservice.ClientService.exceptions.UserNotFoundException;
import com.skilledservice.ClientService.services.ServiceUtils.AppointmentService;
import com.skilledservice.ClientService.services.ServiceUtils.SkillService;
import com.skilledservice.ClientService.services.ServiceUtils.SkilledWorkerService;
import com.skilledservice.ClientService.utils.JwtUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SkilledWorkerServiceImpl implements SkilledWorkerService {

    private final SkillService skillService;
    private final SkilledWorkerRepository skilledWorkerRepository;
    private final AddressServiceImpl addressService;
    private final AppointmentService appointmentService;
    private final SkillRepository skillRepository;
    private final AddressRepository addressRepository;

    @Autowired
    @Lazy
    public SkilledWorkerServiceImpl(SkillService skillService, SkilledWorkerRepository skilledWorkerRepository, AddressServiceImpl addressService, AppointmentService appointmentService, SkillRepository skillRepository, AddressRepository addressRepository) {
        this.skillService = skillService;
        this.skilledWorkerRepository = skilledWorkerRepository;
        this.addressService = addressService;
        this.appointmentService = appointmentService;
        this.skillRepository = skillRepository;
        this.addressRepository = addressRepository;
    }

    @Override
    public Long getNumberOfUsers() {
        return skilledWorkerRepository.count();
    }

    @Override
    public SkilledWorkerRegistrationResponse registerSkilledWorker(RegistrationRequest registrationRequest) {
        validateEmail(registrationRequest.getEmail());
        validatePassword(registrationRequest.getPassword());

        SkilledWorker skilledWorker = new SkilledWorker();
        skilledWorker.setFullName(registrationRequest.getFullName());
        skilledWorker.setEmail(registrationRequest.getEmail());
        skilledWorker.setPassword(registrationRequest.getPassword());
        skilledWorker = skilledWorkerRepository.save(skilledWorker);
        return getSkilledWorkerRegistrationResponse(skilledWorker);
    }

    private static SkilledWorkerRegistrationResponse getSkilledWorkerRegistrationResponse(SkilledWorker skilledWorker) {
        SkilledWorkerRegistrationResponse registrationResponse = new SkilledWorkerRegistrationResponse();
        registrationResponse.setSkilledWorkerId(skilledWorker.getId());
        registrationResponse.setMessage("registration successful");
        return registrationResponse;
    }

    @Override
    public AddSkillResponse addSkill(AddSkillRequest addSkillRequest) {
        return skillService.addSkill(addSkillRequest);
    }

    @Override
    public SkilledWorker findById(Long skilledWorkerId) {
        return skilledWorkerRepository.findById(skilledWorkerId).orElseThrow(() -> new GuildWorkmanException("user not found"));
    }
    @Override
    public SkilledWorker findSkillByFullName(String skilledWorkerFullName) {
        return skilledWorkerRepository.findSkillByFullName(skilledWorkerFullName).orElseThrow(() -> new GuildWorkmanException("user not found"));
    }
    @Override
    public AcceptAppointmentResponse acceptAppointment(AcceptAppointmentRequest acceptAppointmentRequest) {
        SkilledWorker skilledWorker = skilledWorkerRepository.findById(acceptAppointmentRequest.getId())
                .orElseThrow(()-> new GuildWorkmanException("user not found"));
        Appointment appointment = appointmentService.findAppointmentById(acceptAppointmentRequest.getId())
                .orElseThrow(()-> new GuildWorkmanException("appointment not found"));
        skilledWorker.getAppointment().add(appointment);
        return appointmentService.acceptAppointment(acceptAppointmentRequest);
    }

    private void validateEmail(String email) {
        if (!email.matches( "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
            throw new InvalidEmailFoundException("Invalid Email");
        }
    }
    private static  void validatePassword(String password){
        if (password.length() < 8) {
            throw new InvalidPasswordException("Password does not meet complexity requirements");
        }
        if (!password.matches("[a-zA-Z0-9]*")) {
            throw new InvalidPasswordException("Password must be alphanumeric");
        }
        if (!password.matches(".*\\d.*")) {
            throw new InvalidPasswordException("Password must contain at least one digit");
        }
    }

    @Override
    public LoginResponse login(LoginRequest loginRequest) {
        String email = loginRequest.getEmail();
        String password = loginRequest.getPassword();
        return checkLoginDetail(email, password);
    }


    private LoginResponse checkLoginDetail(String email, String password) {
        Optional<SkilledWorker> foundSkilledWorker = skilledWorkerRepository.findByEmail(email);
        if (foundSkilledWorker.isPresent()){
            SkilledWorker skilledWorker = foundSkilledWorker.get();
            if (skilledWorker.getPassword().equals(password)) {
                return loginResponseMapper(skilledWorker);
            } else {
                throw new GuildWorkmanException("Invalid username or password");
            }
        } else {
            throw new GuildWorkmanException("Invalid username or password");
        }
    }

    private LoginResponse loginResponseMapper(SkilledWorker skilledWorker) {
        LoginResponse loginResponse = new LoginResponse();
        String accessToken = JwtUtils.generateAccessToken(skilledWorker.getId());
        BeanUtils.copyProperties(skilledWorker, loginResponse);
        loginResponse.setJwtToken(accessToken);
        loginResponse.setUserId(skilledWorker.getId());
        loginResponse.setMessage("Login Successful");

        return loginResponse;
    }

    @Override
    public UpdateSkilledWorkerResponse updateSkilledWorkerProfile(UpdateSkilledWorkerRequest request) {

        if (request.getSkilledWorkerId() == null) throw new UserNotFoundException("SkilledWorker ID must not be null");

        SkilledWorker found = skilledWorkerRepository.findById(request.getSkilledWorkerId())
                .orElseThrow(()-> new UserNotFoundException("user not found"));
        found.setFullName(request.getFullName());
        found.setEmail(request.getEmail());
        found.setUsername(request.getUsername());
        found.setPhoneNumber(request.getPhoneNumber());
        found.setPassword(request.getPassword());
        Address address = new Address();
        address.setHouseNumber(request.getHouseNumber());
        address.setStreet(request.getStreet());
        address.setArea(request.getArea());
        found.setAddress(address);
        addressRepository.save(address);
        skilledWorkerRepository.save(found);

        UpdateSkilledWorkerResponse response = getUpdateSkilledWorkerResponse(request, found);
        return response;
    }

    private static UpdateSkilledWorkerResponse getUpdateSkilledWorkerResponse(UpdateSkilledWorkerRequest request, SkilledWorker found) {
        UpdateSkilledWorkerResponse response = new UpdateSkilledWorkerResponse();
        response.setSkilledWorkerId(found.getId());
        response.setFullName(request.getFullName());
        response.setEmail(request.getEmail());
        response.setUsername(request.getUsername());
        response.setPhoneNumber(request.getPhoneNumber());
        response.setPassword(request.getPassword());
        response.setHouseNumber(request.getHouseNumber());
        response.setStreet(request.getStreet());
        response.setArea(request.getArea());
        return response;
    }


    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public List<SkilledWorker> findWorkersNear(double lat, double lon, double radius) {
        List<SkilledWorker> allWorkers = skilledWorkerRepository.findAll();
        return allWorkers.stream()
                .filter(worker -> calculateDistance(lat, lon, worker.getLatitude(), worker.getLongitude()) <= radius)
                .collect(Collectors.toList());
    }


}

