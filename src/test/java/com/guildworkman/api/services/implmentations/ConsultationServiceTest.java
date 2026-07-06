package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.models.Client;
import com.guildworkman.api.data.models.Consultation;
import com.guildworkman.api.data.models.ConsultationAvailability;
import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.repository.ClientRepository;
import com.guildworkman.api.data.repository.ConsultationAvailabilityRepository;
import com.guildworkman.api.data.repository.ConsultationRepository;
import com.guildworkman.api.data.repository.SkilledWorkerRepository;
import com.guildworkman.api.dto.responses.ConsultationResponse;
import com.guildworkman.api.services.ServiceUtils.ConsultationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class ConsultationServiceTest {

    @Autowired
    private ConsultationService consultationService;

    @Autowired
    private ConsultationAvailabilityRepository consultationAvailabilityRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private SkilledWorkerRepository skilledWorkerRepository;

    private Client client;
    private SkilledWorker skilledWorker;

    @BeforeEach
    void setUp() {
        Client newClient = new Client();
        newClient.setFullName("Consultation Test Client");
        newClient.setEmail("consultation.client@example.com");
        newClient.setPassword("password1");
        client = clientRepository.save(newClient);

        SkilledWorker newWorker = new SkilledWorker();
        newWorker.setFullName("Consultation Test Worker");
        newWorker.setEmail("consultation.worker@example.com");
        newWorker.setPassword("password1");
        skilledWorker = skilledWorkerRepository.save(newWorker);
    }

    @Test
    void testBookConsultation() {
        ConsultationResponse bookedConsultation = consultationService.bookConsultation(
                client.getId(),
                skilledWorker.getId(),
                "Need electrical repair"
        );
        assertNotNull(bookedConsultation);
        assertNotNull(bookedConsultation.getMessage());
    }

    @Test
    void testScheduleAvailability() {
        ConsultationAvailability consultationAvailability = new ConsultationAvailability();
        consultationAvailability.setClientAvailability(LocalDateTime.now());
        consultationAvailability.setWorkerAvailability(LocalDateTime.now().plusHours(1));
        consultationAvailability.setConsultation(consultationAvailability.getConsultation());
        consultationAvailability.setId(1L);
        assertNotNull(consultationAvailability);
    }

    @Test
    void testScheduleAvailability_ConsultationNotFound() {
        assertThrows(RuntimeException.class, () -> {
            consultationService.scheduleAvailability(999L, LocalDateTime.now(), LocalDateTime.now().plusHours(1));
        });
    }
    
}
