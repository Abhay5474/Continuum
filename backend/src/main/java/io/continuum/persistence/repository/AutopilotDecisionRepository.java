package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotDecisionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotDecisionRepository extends JpaRepository<AutopilotDecisionEntity, Long> {

    Page<AutopilotDecisionEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);
}
