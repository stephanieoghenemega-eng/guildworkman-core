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
import com.guildworkman.api.dto.responses.SkilledWorkerRegistrationResponse;
import com.guildworkman.api.dto.responses.ViewAllAppointmentsResponse;
import com.guildworkman.api.services.ServiceUtils.ClientService;
import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
    @Autowired
    private SkilledWorkerService skilledWorkerService;

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

    @Test
    public void viewAllAppointment_returnsEveryAppointment_withIdAndStatus() {
        ClientRegistrationResponse client = clientService.registerClient(getRegistrationRequest());
        Long clientId = client.getClientId();

        clientService.bookAppointment(
                bookRequest(clientId, Category.ELECTRICAL, LocalDateTime.now().plusDays(2)));
        clientService.bookAppointment(
                bookRequest(clientId, Category.PLUMBING, LocalDateTime.now().plusDays(5)));

        List<ViewAllAppointmentsResponse> appointments = clientService.viewAllAppointment(clientId);

        // Previously this returned only appointments.get(0), mapped to two fields.
        assertThat(appointments).hasSize(2);
        assertThat(appointments).allSatisfy(appointment -> {
            // The id is what cancel/update/delete take as ?appointmentId= — without it
            // the whole appointment-management flow is unreachable from a client.
            assertThat(appointment.getId()).isNotNull();
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
            assertThat(appointment.getScheduleTime()).isNotNull();
        });
        assertThat(appointments)
                .extracting(ViewAllAppointmentsResponse::getCategory)
                .containsExactlyInAnyOrder(Category.ELECTRICAL, Category.PLUMBING);
    }

    @Test
    public void bookAppointment_recordsTheBookedWorker_andAmount() {
        ClientRegistrationResponse client = clientService.registerClient(getRegistrationRequest());

        RegistrationRequest workerRequest = new RegistrationRequest();
        workerRequest.setFullName("Chidi Okonkwo");
        workerRequest.setEmail("chidi@sparks.com");
        workerRequest.setPassword("password1");
        SkilledWorkerRegistrationResponse worker =
                skilledWorkerService.registerSkilledWorker(workerRequest);

        BookAppointmentRequest request =
                bookRequest(client.getClientId(), Category.ELECTRICAL, LocalDateTime.now().plusDays(3));
        request.setSkilledWorkerId(worker.getSkilledWorkerId());
        request.setAmount(BigDecimal.valueOf(8800));
        clientService.bookAppointment(request);

        List<ViewAllAppointmentsResponse> appointments =
                clientService.viewAllAppointment(client.getClientId());

        assertThat(appointments).hasSize(1);
        ViewAllAppointmentsResponse appointment = appointments.get(0);

        // The API previously never recorded WHICH worker was booked — the request
        // had no skilledWorkerId and nothing set Appointment.skilledWorker.
        assertThat(appointment.getWorker()).isNotNull();
        assertThat(appointment.getWorker().getId()).isEqualTo(worker.getSkilledWorkerId());
        assertThat(appointment.getWorker().getFullName()).isEqualTo("Chidi Okonkwo");
        assertThat(appointment.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(8800));
    }

    @Test
    public void bookAppointment_withoutAWorker_stillSucceeds() {
        // skilledWorkerId is optional, so existing callers keep working.
        ClientRegistrationResponse client = clientService.registerClient(getRegistrationRequest());
        clientService.bookAppointment(
                bookRequest(client.getClientId(), Category.PLUMBING, LocalDateTime.now().plusDays(1)));

        List<ViewAllAppointmentsResponse> appointments =
                clientService.viewAllAppointment(client.getClientId());

        assertThat(appointments).hasSize(1);
        assertThat(appointments.get(0).getWorker()).isNull();
    }

    @Test
    public void viewAllAppointment_forClientWithNoAppointments_returnsEmptyList() {
        ClientRegistrationResponse client = clientService.registerClient(getRegistrationRequest());

        // Previously this threw IllegalArgumentException; an empty list is the correct
        // result for a client who simply hasn't booked anything yet.
        assertThat(clientService.viewAllAppointment(client.getClientId())).isEmpty();
    }

    private static @NotNull BookAppointmentRequest bookRequest(Long clientId,
                                                               Category category,
                                                               LocalDateTime scheduleTime) {
        BookAppointmentRequest request = new BookAppointmentRequest();
        request.setClientId(clientId);
        request.setCategory(category);
        request.setStatus(AppointmentStatus.SCHEDULED);
        request.setScheduleTime(scheduleTime);
        return request;
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
