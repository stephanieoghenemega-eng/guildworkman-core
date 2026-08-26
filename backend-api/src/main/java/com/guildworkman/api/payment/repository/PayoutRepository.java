package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findByReference(String reference);
}
