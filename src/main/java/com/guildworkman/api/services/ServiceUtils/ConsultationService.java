package com.guildworkman.api.services.ServiceUtils;
import com.guildworkman.api.data.models.ConsultationAvailability;
import com.guildworkman.api.dto.responses.ConsultationResponse;

import java.time.LocalDateTime;

public interface ConsultationService {
    ConsultationResponse bookConsultation(Long clientId, Long workerId,String details);
    ConsultationAvailability scheduleAvailability(Long consultationId,
                                                  LocalDateTime clientAvailability,
                                                  LocalDateTime workerAvailability);



}
