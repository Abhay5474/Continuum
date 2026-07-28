package io.continuum.persistence.repository;

import io.continuum.persistence.entity.LoopSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoopSettingRepository extends JpaRepository<LoopSettingEntity, String> {
}
