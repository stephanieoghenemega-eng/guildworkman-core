package com.guildworkman.api.services;

import com.guildworkman.api.data.constants.AppointmentStatus;
import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.data.models.Client;
import com.guildworkman.api.data.repository.AddressRepository;
import com.guildworkman.api.data.repository.AppointmentRepository;
import com.guildworkman.api.dto.requests.BookAppointmentRequest;
import com.guildworkman.api.dto.requests.RegistrationRequest;
import com.guildworkman.api.dto.requests.UpdateClientRequest;
import com.guildworkman.api.dto.responses.ClientRegistrationResponse;
import com.guildworkman.api.data.repository.ClientRepository;
import com.guildworkman.api.dto.responses.UpdateClientResponse;
import com.guildworkman.api.dto.responses.UpdateSkilledWorkerResponse;
import com.guildworkman.api.services.ServiceUtils.ClientService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static com.guildworkman.api.data.constants.AppointmentStatus.CANCELLED;
import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
@Transactional
//@Sql(scripts = "/db/data.sql")
public class ClientServiceTest {
    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;

    @BeforeEach
    public void setUp() {
      appointmentRepository.deleteAll();
    }

    @Test
    public void registerClient() {
        long before = clientService.getNumberOfUsers();
        RegistrationRequest registerClientRequest = getRegistrationRequest();
        ClientRegistrationResponse response = clientService.registerClient(registerClientRequest);
        assertThat(response).isNotNull();
        assertThat(clientService.getNumberOfUsers()).isEqualTo(before + 1);

    }

    private static @NotNull RegistrationRequest getRegistrationRequest() {
        RegistrationRequest registerClientRequest = new RegistrationRequest();
        registerClientRequest.setFullName("John Doe");
        registerClientRequest.setEmail("john@doe.com");
        registerClientRequest.setPassword("password1");
        return registerClientRequest;
    }

    @Test
    public void updateUserProfileTest() {
        long before = clientService.getNumberOfUsers();
        UpdateClientRequest updateRequest = new UpdateClientRequest();
        RegistrationRequest registerClientRequest = getRegistrationRequest();
        ClientRegistrationResponse response = clientService.registerClient(registerClientRequest);
        assertThat(response).isNotNull();
        assertThat(clientService.getNumberOfUsers()).isEqualTo(before + 1);

        updateRequest.setClientId(response.getClientId());
        updateRequest.setUsername("Jdoe");
        updateRequest.setPassword("password1");
        updateRequest.setHouseNumber("312");
        updateRequest.setStreet("Herbert Macaulay way");
        updateRequest.setArea("Yaba");
        UpdateClientResponse updateResponse = clientService.updateClientProfile(updateRequest);
        assertThat(updateResponse).isNotNull();
        assertThat(clientService.getNumberOfUsers()).isEqualTo(before + 1);
        assertThat(updateResponse.getClientId()).isEqualTo(response.getClientId());

    }

//    @Test
//    public void testThatClientCan_bookAppointmentTest() {
//        BookAppointmentRequest request = new BookAppointmentRequest();
//        request.setClientId(2L);
//        request.setCategory(Category.ELECTRICAL);
//        request.setStatus(AppointmentStatus.SCHEDULED);
//        request.setScheduleTime(java.time.LocalDateTime.now().plusDays(6));
//        request.setSkilledWorkerId(204L);
//        clientService.bookAppointment(request);
//        assertThat(clientRepository.findById(2L).get()
//                .getAppointment().size()).isEqualTo(1);
//
//    }

//    @Test
//    public void testThatClientCan_cancelAppointmentTest() {
//        BookAppointmentRequest request = new BookAppointmentRequest();
//        request.setClientId(3L);
//        request.setCategory(Category.ELECTRICAL);
//        request.setStatus(AppointmentStatus.SCHEDULED);
//        request.setScheduleTime(java.time.LocalDateTime.now().plusDays(6));
//        request.setSkilledWorkerId(204L);
//        clientService.bookAppointment(request);
//        clientService.cancelAppointment(1L,3L);
//        Client client = clientRepository.findById(3L).orElseThrow();
//        assertThat(client.getAppointment().size()).isEqualTo(1);
//        assertThat(client.getAppointment().getFirst().getStatus()).isEqualTo(CANCELLED);
//    }
//    @Test
//    public void testThatClientCanDeleteAppointment(){
//        BookAppointmentRequest request = new BookAppointmentRequest();
//        request.setClientId(2L);
//        request.setCategory(Category.ELECTRICAL);
//        request.setStatus(AppointmentStatus.SCHEDULED);
//        request.setScheduleTime(java.time.LocalDateTime.now().plusDays(6));
//        request.setSkilledWorkerId(202L);
//        clientService.bookAppointment(request);
//        assertThat(clientRepository.findById(2L).get()
//                .getAppointment().size()).isEqualTo(1);
//
//    }
}
