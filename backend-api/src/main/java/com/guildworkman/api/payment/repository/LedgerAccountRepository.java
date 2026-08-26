package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findByCode(String code);
}
