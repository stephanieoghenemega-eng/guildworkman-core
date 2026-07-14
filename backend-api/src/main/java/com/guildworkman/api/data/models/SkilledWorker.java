package com.guildworkman.api.data.models;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.data.constants.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static java.time.LocalDateTime.now;

@Setter
@Getter
@Entity
@Table(name = "skilled_workers")
public class SkilledWorker {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String fullName;
    @Column(unique = true)
    private String username;
    private String password;
    @Column(unique = true)
    private String phoneNumber;
    @Column(unique = true)
    private String email;
    @Setter(AccessLevel.NONE)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime timeCreated;
    @Setter(AccessLevel.NONE)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime timeUpdated;
    @OneToOne
    private Address address;
    @Enumerated(EnumType.STRING)
    private Category category;
    @OneToMany(mappedBy = "skilledWorker",
            cascade = CascadeType.ALL,orphanRemoval = true,fetch = FetchType.EAGER)
    // Initialised for the same reason as Client.appointment: a freshly-persisted
    // SkilledWorker whose collection Hibernate hasn't hydrated is otherwise a null
    // list waiting to NPE on the first add/remove.
    private List<Appointment> appointment = new ArrayList<>();

    @PrePersist
    private void setTimeCreated(){
        this.timeCreated= now();
    }
    @PreUpdate
    private void setTimeUpdated(){
        this.timeUpdated= now();
    }

    private double latitude;
    private double longitude;

}


