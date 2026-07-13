package com.guildworkman.api.services;

import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.repository.SkilledWorkerRepository;
import com.guildworkman.api.dto.requests.RegistrationRequest;
import com.guildworkman.api.dto.requests.UpdateSkilledWorkerRequest;
import com.guildworkman.api.dto.responses.SkilledWorkerRegistrationResponse;
import com.guildworkman.api.data.repository.AddressRepository;
import com.guildworkman.api.dto.responses.UpdateSkilledWorkerResponse;
import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;


@SpringBootTest
@Sql(scripts = {"/db/data.sql"})
public class SkilledWorkerServiceTest {
    @Autowired
    private SkilledWorkerService skilledWorkerService;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private SkilledWorkerRepository skilledWorkerRepository;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }


    @Test
    public void SkilledWorkerRegistrationTest() {
        long before = skilledWorkerService.getNumberOfUsers();
        RegistrationRequest registrationRequest = getRegistrationRequest();
        SkilledWorkerRegistrationResponse response = skilledWorkerService.registerSkilledWorker(registrationRequest);
        assertThat(skilledWorkerService.getNumberOfUsers()).isEqualTo(before + 1);
        assertThat(response).isNotNull();
    }

    private RegistrationRequest getRegistrationRequest() {
        RegistrationRequest registrationRequest = new RegistrationRequest();
        registrationRequest.setFullName("Fitzgerald McDonald");
        registrationRequest.setEmail("fitzgerald@gmail.com");
        registrationRequest.setPassword("password1");

        return registrationRequest;
    }

    @Test
    public void UpdateSkilledWorkerTest() {
//        SkilledWorker skilledWorker = new SkilledWorker();
        UpdateSkilledWorkerRequest request = new UpdateSkilledWorkerRequest();
        RegistrationRequest registrationRequest = getRegistrationRequest();
        SkilledWorkerRegistrationResponse response = skilledWorkerService.registerSkilledWorker(registrationRequest);


        request.setSkilledWorkerId(response.getSkilledWorkerId());
        request.setPassword("password2");
        request.setPhoneNumber("1234567891");
        request.setUsername("Fitty");
        request.setHouseNumber("111");
        request.setStreet("Street 1");
        request.setArea("Iwaya");
        UpdateSkilledWorkerResponse response1 = skilledWorkerService.updateSkilledWorkerProfile(request);
        assertThat(response1).isNotNull();
        assertThat(response1.getSkilledWorkerId()).isEqualTo(response.getSkilledWorkerId());
    }
    @Test
    public void testFindWorkersNear() {
        double clientLat = 39.75621;
        double clientLon = -104.99404;
        double radius = 10.0;
        List<SkilledWorker> result = skilledWorkerService.findWorkersNear(clientLat, clientLon, radius);
        assertEquals(4, result.size());
        result.forEach(worker -> System.out.println(worker.getFullName()));
    }
    @Test
    public void testFindWorkersNear_NoWorkersInRange() {
        double clientLat = 40.00000;
        double clientLon = -105.00000;
        double radius = 5.0;
        List<SkilledWorker> result = skilledWorkerService.findWorkersNear(clientLat, clientLon, radius);
        assertEquals(0, result.size());
    }

}
