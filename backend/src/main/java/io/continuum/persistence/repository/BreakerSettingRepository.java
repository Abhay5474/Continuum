package io.continuum.persistence.repository;

import io.continuum.persistence.entity.BreakerSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakerSettingRepository extends JpaRepository<BreakerSettingEntity, String> {
}
