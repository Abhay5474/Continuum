package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MmuRequestMetricEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MmuRequestMetricRepository extends JpaRepository<MmuRequestMetricEntity, Long> {

    List<MmuRequestMetricEntity> findTop200ByDeveloperIdOrderByCreatedAtDesc(String developerId);

    List<MmuRequestMetricEntity> findTop200ByOrderByCreatedAtDesc();
}
