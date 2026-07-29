package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CostAdmissionSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostAdmissionSettingRepository
        extends JpaRepository<CostAdmissionSettingEntity, String> {
}
