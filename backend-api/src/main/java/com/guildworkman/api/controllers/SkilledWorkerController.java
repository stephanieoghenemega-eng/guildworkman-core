package com.guildworkman.api.controllers;

import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.dto.requests.AddSkillRequest;
import com.guildworkman.api.dto.requests.LoginRequest;
import com.guildworkman.api.dto.requests.RegistrationRequest;
import com.guildworkman.api.dto.requests.UpdateSkilledWorkerRequest;
import com.guildworkman.api.dto.responses.ApiResponse;
import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/skilledWorker")
@AllArgsConstructor
public class SkilledWorkerController {

    private final SkilledWorkerService skilledWorkerService;


    @PostMapping("/registerSkilledWorker")
    public ResponseEntity<?> registerSkilledWorker(@RequestBody RegistrationRequest registrationRequest) {
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse
                        (skilledWorkerService.registerSkilledWorker(registrationRequest), true));
    }
    @PostMapping("/addSkill")
    public ResponseEntity<?> addSkill(@RequestBody AddSkillRequest addSkillRequest) {
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse
                        (skilledWorkerService.addSkill(addSkillRequest), true));
    }
    @GetMapping("/findById")
    public ResponseEntity<?> findById(@RequestParam Long skilledWorkerId) {
        return ResponseEntity.ok(skilledWorkerService.findById(skilledWorkerId));
    }
    @GetMapping("/findByFullName")
    public ResponseEntity<?> findSkillByFullName(@RequestParam String skilledWorkerFullName) {
        return ResponseEntity.ok(skilledWorkerService.findSkillByFullName(skilledWorkerFullName));
    }
    @PutMapping("/updateSkilledWorkerProfile")
    public ResponseEntity<?> updateSkilledWorkerProfile(@RequestBody UpdateSkilledWorkerRequest request) {
        return ResponseEntity
                .ok(new ApiResponse(skilledWorkerService.updateSkilledWorkerProfile(request), true));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse(skilledWorkerService.login(loginRequest), true));
    }

    @GetMapping("/nearby")
    public List<SkilledWorker> getWorkersNearby(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "10") double radius
    ) {
        return skilledWorkerService.findWorkersNear(lat, lon, radius);
    }
}
