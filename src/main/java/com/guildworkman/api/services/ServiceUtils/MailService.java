package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.dto.requests.SendMailRequest;

public interface MailService {
    String sendMail(SendMailRequest sendMailRequest);
}
