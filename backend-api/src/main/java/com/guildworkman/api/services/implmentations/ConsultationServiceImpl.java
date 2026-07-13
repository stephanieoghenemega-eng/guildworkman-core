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
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
@NoArgsConstructor
public class ConsultationServiceImpl implements ConsultationService {
    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private SkilledWorkerRepository skilledWorkerRepository;

    @Autowired
    private ConsultationAvailabilityRepository consultationAvailabilityRepository;

    @Override
    public ConsultationResponse bookConsultation(Long clientId, Long workerId, String details) {
            Client client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new IllegalArgumentException("Client not found with id: " + clientId));

            SkilledWorker skilledWorker = skilledWorkerRepository.findById(workerId)
                    .orElseThrow(() -> new IllegalArgumentException("SkilledWorker not found with id: " + workerId));

            Consultation consultation = new Consultation();
            consultation.setClient(client);
            consultation.setWorker(skilledWorker);
            consultation.setConsultationDetails(details);
            consultationRepository.save(consultation);
            ConsultationResponse response = new ConsultationResponse();
            response.setMessage("Booked consultation successful");
            return response;
    }

    @Override
    public ConsultationAvailability scheduleAvailability(Long consultationId, LocalDateTime clientAvailability, LocalDateTime workerAvailability) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));
        ConsultationAvailability availability = new ConsultationAvailability();
        availability.setConsultation(consultation);
        availability.setClientAvailability(clientAvailability);
        availability.setWorkerAvailability(workerAvailability);
        return consultationAvailabilityRepository.save(availability);
    }
}
