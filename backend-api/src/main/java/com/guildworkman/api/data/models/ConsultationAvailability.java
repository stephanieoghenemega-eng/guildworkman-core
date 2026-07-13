package com.guildworkman.api.data.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class ConsultationAvailability {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;

    @Column
    private LocalDateTime clientAvailability;

    @Column
    private LocalDateTime workerAvailability;
}
