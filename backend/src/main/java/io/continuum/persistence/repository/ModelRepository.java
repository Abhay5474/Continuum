package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ModelEntity;
import io.continuum.registry.ModelStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelRepository extends JpaRepository<ModelEntity, Long> {

    Optional<ModelEntity> findByProviderAndModelName(String provider, String modelName);

    List<ModelEntity> findByStatus(ModelStatus status);

    List<ModelEntity> findByProviderAndStatus(String provider, ModelStatus status);
}
