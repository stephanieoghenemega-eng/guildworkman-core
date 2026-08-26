package com.guildworkman.api.payment.repository;

import com.guildworkman.api.payment.model.DiscrepancyStatus;
import com.guildworkman.api.payment.model.ReconciliationDiscrepancy;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, Long> {

    Optional<ReconciliationDiscrepancy> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);

    List<ReconciliationDiscrepancy> findByStatusOrderByDetectedAtDesc(DiscrepancyStatus status, Pageable pageable);

    List<ReconciliationDiscrepancy> findByResourceReferenceOrderByDetectedAtDesc(String resourceReference);

    long countByStatus(DiscrepancyStatus status);
}
