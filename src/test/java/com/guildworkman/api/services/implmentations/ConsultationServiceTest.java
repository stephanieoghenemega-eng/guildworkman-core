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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
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

    @Test
    void testBookConsultation() {
        Client client = clientRepository.findById(1L)
                .orElseThrow(() -> new IllegalArgumentException("Client not found with id: 1L"));

        SkilledWorker skilledWorker = skilledWorkerRepository.findById(1L)
                .orElseThrow(() -> new IllegalArgumentException("SkilledWorker not found with id: 1L"));

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
