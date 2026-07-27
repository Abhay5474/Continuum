package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CascadeSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CascadeSettingRepository extends JpaRepository<CascadeSettingEntity, String> {
}
