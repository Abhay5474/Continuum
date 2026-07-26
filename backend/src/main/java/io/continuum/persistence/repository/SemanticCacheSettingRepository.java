package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SemanticCacheSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticCacheSettingRepository extends JpaRepository<SemanticCacheSettingEntity, String> {
}
