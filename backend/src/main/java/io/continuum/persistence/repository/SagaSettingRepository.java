package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SagaSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaSettingRepository extends JpaRepository<SagaSettingEntity, String> {
}
