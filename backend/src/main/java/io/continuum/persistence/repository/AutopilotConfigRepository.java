package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutopilotConfigRepository extends JpaRepository<AutopilotConfigEntity, String> {

    List<AutopilotConfigEntity> findByEnabledTrue();
}
