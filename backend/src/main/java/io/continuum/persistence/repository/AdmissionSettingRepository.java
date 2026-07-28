package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AdmissionSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdmissionSettingRepository extends JpaRepository<AdmissionSettingEntity, String> {
}
