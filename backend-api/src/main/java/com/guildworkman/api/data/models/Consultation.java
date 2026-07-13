package com.guildworkman.api.data.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Setter
@Getter
public class Consultation {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(cascade = {CascadeType.MERGE, CascadeType.REFRESH})
        private Client client;
        @ManyToOne(cascade = {CascadeType.MERGE, CascadeType.REFRESH})
        private SkilledWorker worker;
        @Column
        private String consultationDetails;
        @OneToOne(mappedBy = "consultation")
        private ConsultationAvailability availability;

}
