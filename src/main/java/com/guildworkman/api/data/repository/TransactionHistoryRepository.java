package com.guildworkman.api.data.repository;

import com.guildworkman.api.data.models.TransactionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionHistoryRepository extends JpaRepository<TransactionHistory, Long> {
}
