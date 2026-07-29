package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CounterfactualSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterfactualSettingRepository
        extends JpaRepository<CounterfactualSettingEntity, String> {
}
