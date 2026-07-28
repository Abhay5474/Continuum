package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MmuSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MmuSettingRepository extends JpaRepository<MmuSettingEntity, String> {
}
