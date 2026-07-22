package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CompressionMetricEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompressionMetricRepository extends JpaRepository<CompressionMetricEntity, Long> {

    List<CompressionMetricEntity> findTop200ByDeveloperIdOrderByCreatedAtDesc(String developerId);

    List<CompressionMetricEntity> findTop200ByOrderByCreatedAtDesc();
}
