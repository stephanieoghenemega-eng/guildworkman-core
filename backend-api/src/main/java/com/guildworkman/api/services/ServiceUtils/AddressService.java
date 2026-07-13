package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.data.models.Address;
import com.guildworkman.api.dto.requests.RegistrationRequest;

public interface AddressService {
    Address createAddress(RegistrationRequest registrationRequest);
}
