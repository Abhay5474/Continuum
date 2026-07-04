package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DivergenceResolutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DivergenceResolutionRepository extends JpaRepository<DivergenceResolutionEntity, Long> {

    List<DivergenceResolutionEntity> findTop100ByOrderByResolvedAtDesc();

    List<DivergenceResolutionEntity> findByWorkflowIdOrderByResolvedAtAsc(String workflowId);
}
