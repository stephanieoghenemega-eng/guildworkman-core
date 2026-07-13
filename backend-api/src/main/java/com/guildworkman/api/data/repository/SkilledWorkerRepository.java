package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.SkilledWorker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SkilledWorkerRepository extends JpaRepository<SkilledWorker, Long> {

    Optional<SkilledWorker> findById(Long Id);

    Optional<SkilledWorker> findByEmail(String email);

    Optional<SkilledWorker> findSkillByFullName(String skilledWorkerFullName);
}



