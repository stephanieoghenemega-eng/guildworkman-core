package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
