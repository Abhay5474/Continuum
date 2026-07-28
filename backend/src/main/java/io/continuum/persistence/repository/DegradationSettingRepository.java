package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DegradationSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DegradationSettingRepository
        extends JpaRepository<DegradationSettingEntity, String> {
}
