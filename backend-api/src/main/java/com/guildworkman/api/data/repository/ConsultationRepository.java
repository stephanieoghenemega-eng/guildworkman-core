package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationRepository extends JpaRepository<Consultation,Long> {
}
