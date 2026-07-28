package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SchedulingSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulingSettingRepository extends JpaRepository<SchedulingSettingEntity, String> {
}
