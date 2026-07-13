package com.guildworkman.api.dto.responses;

import com.guildworkman.api.data.models.Client;
import com.guildworkman.api.data.models.SkilledWorker;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ConsultationResponse {
    private String message;
    private Client client;;
    private SkilledWorker skilledWorker;
}
