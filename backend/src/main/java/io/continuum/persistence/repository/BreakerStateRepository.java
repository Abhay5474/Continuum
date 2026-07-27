package io.continuum.persistence.repository;

import io.continuum.persistence.entity.BreakerStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BreakerStateRepository extends JpaRepository<BreakerStateEntity, Long> {

    Optional<BreakerStateEntity> findByDeveloperIdAndProviderAndModel(
            String developerId, String provider, String model);

    List<BreakerStateEntity> findByDeveloperId(String developerId);

    void deleteByDeveloperId(String developerId);
}
