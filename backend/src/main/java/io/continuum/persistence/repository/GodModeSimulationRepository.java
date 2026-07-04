package io.continuum.persistence.repository;

import io.continuum.persistence.entity.GodModeSimulationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GodModeSimulationRepository extends JpaRepository<GodModeSimulationEntity, Long> {

    List<GodModeSimulationEntity> findTop50ByDeveloperIdOrderByCreatedAtDesc(String developerId);
}
