package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DeveloperApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeveloperApiKeyRepository extends JpaRepository<DeveloperApiKeyEntity, Long> {

    List<DeveloperApiKeyEntity> findByKeyPrefix(String keyPrefix);

    List<DeveloperApiKeyEntity> findByDeveloperId(String developerId);
}
