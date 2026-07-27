package io.continuum.persistence.repository;

import io.continuum.persistence.entity.UncertaintySettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UncertaintySettingRepository extends JpaRepository<UncertaintySettingEntity, String> {
}
