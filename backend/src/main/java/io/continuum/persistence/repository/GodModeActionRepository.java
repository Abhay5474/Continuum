package io.continuum.persistence.repository;

import io.continuum.persistence.entity.GodModeActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GodModeActionRepository extends JpaRepository<GodModeActionEntity, Long> {

    List<GodModeActionEntity> findTop100ByDeveloperIdOrderByCreatedAtDesc(String developerId);

    long countByDeveloperId(String developerId);
}
