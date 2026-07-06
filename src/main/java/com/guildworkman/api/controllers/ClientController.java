package com.guildworkman.api.controllers;

import com.guildworkman.api.data.models.ConsultationAvailability;
import com.guildworkman.api.dto.requests.*;
import com.guildworkman.api.dto.responses.ApiResponse;
import com.guildworkman.api.dto.responses.ConsultationResponse;
import com.guildworkman.api.services.ServiceUtils.ClientService;
import com.guildworkman.api.services.ServiceUtils.ConsultationService;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.CREATED;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/client")
@AllArgsConstructor
public class ClientController {

    private static final Logger log = LoggerFactory.getLogger(ClientController.class);
    private final ClientService clientService;
    private final ConsultationService consultationService;


    @PostMapping("/bookAppointment")
    public ResponseEntity<?>bookAppointment(@RequestBody BookAppointmentRequest bookAppointmentRequest){
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse
                        (clientService.bookAppointment(bookAppointmentRequest),true));
    }

    @PostMapping("/registerClient")
    public ResponseEntity<?> registerClient(@RequestBody RegistrationRequest registrationRequest){
//        log.info("Registering Client => {}", registrationRequest.toString());
        return ResponseEntity.status(CREATED)
               .body(new ApiResponse
                        (clientService.registerClient(registrationRequest),true));
    }
    @PutMapping("/cancelAppointment")
    public ResponseEntity<?> cancelAppointment(@RequestParam Long appointmentId, CancelAppointmentRequest request){
        return ResponseEntity.status(CREATED)
               .body(new ApiResponse
                        (clientService.cancelAppointment(appointmentId,request),true));
    }

    @PutMapping("/updateAppointment")
    public ResponseEntity<?> updateAppointment(@RequestParam Long appointmentId, @RequestBody UpdateAppointmentRequest request){
        return ResponseEntity.status(CREATED)
               .body(new ApiResponse
                        (clientService.updateAppointment(appointmentId, request),true));
    }
    @DeleteMapping("/deleteAppointment")
    public ResponseEntity<?> deleteAppointment(@RequestParam Long appointmentId,@RequestBody DeleteAppointmentRequest request){
        return ResponseEntity.status(CREATED)
               .body(new ApiResponse
                        (clientService.deleteAppointment(appointmentId,request),true));
    }

    @GetMapping("/viewAllAppointment")
    public ResponseEntity<?> viewAllAppointment(@RequestParam Long clientId){
        System.out.println("Received clientId: " + clientId);
        return ResponseEntity.status(CREATED)
               .body(new ApiResponse
                        (clientService.viewAllAppointment(clientId),true));
    }

    @PutMapping("/updateClientProfile")
    public ResponseEntity<?> updateClientProfile(@RequestBody UpdateClientRequest request) {
        return ResponseEntity
                .ok(new ApiResponse(clientService.updateClientProfile(request), true));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest){
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse(clientService.login(loginRequest),true));
    }

    @RequestMapping(value = "/api/**", method = RequestMethod.OPTIONS)
    public ResponseEntity<?> handleOptions() {
        return ResponseEntity.ok().build();
    }
    @PostMapping("/consult")
    public ResponseEntity<ConsultationResponse> bookConsultation(
            @RequestParam Long clientId,
            @RequestParam Long workerId,
            @RequestParam String details) {
        ConsultationResponse response = consultationService.bookConsultation(clientId, workerId, details);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
    @PostMapping("/{consultationId}/availability")
    public ResponseEntity<ConsultationAvailability> scheduleAvailability(
            @PathVariable Long consultationId,
            @RequestParam LocalDateTime clientAvailability,
            @RequestParam LocalDateTime workerAvailability) {
        ConsultationAvailability availability = consultationService.scheduleAvailability(
                consultationId, clientAvailability, workerAvailability);
        return new ResponseEntity<>(availability, HttpStatus.CREATED);
    }

}
