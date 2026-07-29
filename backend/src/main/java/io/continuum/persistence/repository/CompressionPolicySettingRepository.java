package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CompressionPolicySettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompressionPolicySettingRepository
        extends JpaRepository<CompressionPolicySettingEntity, String> {
}
