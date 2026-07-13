package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findAllById(Long id);
    Long findByClientId(Long id);
    Optional<Appointment> findById(Long id);
}
