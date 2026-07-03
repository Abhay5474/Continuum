package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotTelemetryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotTelemetryRepository extends JpaRepository<AutopilotTelemetryEntity, Long> {

    Page<AutopilotTelemetryEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);
}
