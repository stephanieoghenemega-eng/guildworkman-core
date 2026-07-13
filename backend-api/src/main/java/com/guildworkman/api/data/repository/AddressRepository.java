package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {
}
