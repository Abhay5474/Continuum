package io.continuum.persistence.repository;

import io.continuum.persistence.entity.GodModeConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GodModeConfigRepository extends JpaRepository<GodModeConfigEntity, String> {

    List<GodModeConfigEntity> findByEnabledTrue();
}
