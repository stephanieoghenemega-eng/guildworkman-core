package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.repository.SkilledWorkerRepository;
import com.guildworkman.api.dto.requests.RegistrationRequest;
import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SkilledWorkerServiceImplTest {

    @Autowired
    private SkilledWorkerService skilledWorkerService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SkilledWorkerRepository skilledWorkerRepository;

    @Test
    public void testLogin(){
        RegistrationRequest request = new RegistrationRequest();
        request.setEmail("olodo1@gmail.com");
        request.setPassword("olodoolodo1");
        skilledWorkerService.registerSkilledWorker(request);

        SkilledWorker found = skilledWorkerRepository.findByEmail("olodo@gmail.com").orElseThrow();
        System.out.println(found.getPassword());

        System.out.println(passwordEncoder.matches(found.getPassword(), "olodoolodo1"));
    }
}