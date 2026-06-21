package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ProviderStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderStatsRepository extends JpaRepository<ProviderStatsEntity, String> {
}
