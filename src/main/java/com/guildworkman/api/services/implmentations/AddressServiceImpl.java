package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.models.Address;
import com.guildworkman.api.data.repository.AddressRepository;
import com.guildworkman.api.dto.requests.RegistrationRequest;
import com.guildworkman.api.services.ServiceUtils.AddressService;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;

@Service
public class AddressServiceImpl implements AddressService {
    private final ModelMapper modelMapper;
    private final AddressRepository addressRepository;

    public AddressServiceImpl(ModelMapper modelMapper, AddressRepository addressRepository) {
        this.modelMapper = modelMapper;
        this.addressRepository = addressRepository;
    }

    @Override
    public Address createAddress(RegistrationRequest registrationRequest) {
        Address address = modelMapper.map(registrationRequest, Address.class);
        return addressRepository.save(address);
    }

}
