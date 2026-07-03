package com.skilledservice.ClientService.controllers;

import com.skilledservice.ClientService.dto.requests.RegistrationRequest;
import com.skilledservice.ClientService.dto.requests.SendMailRequest;
import com.skilledservice.ClientService.dto.responses.ApiResponse;
import com.skilledservice.ClientService.services.ServiceUtils.MailService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static org.springframework.http.HttpStatus.CREATED;

@CrossOrigin(origins = "https://guildworkman.vercel.app/")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/mail")
public class MailController {
    private final MailService mailService;

    @PostMapping("/sendMail")
    public ResponseEntity<?>sendMail(@RequestBody SendMailRequest sendMailRequest) {
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse(mailService.sendMail(sendMailRequest),true));

    }
}
