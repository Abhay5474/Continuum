package io.continuum.persistence.repository;

import io.continuum.persistence.entity.WorkflowHealingLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WorkflowHealingLogRepository extends JpaRepository<WorkflowHealingLogEntity, Long> {

    List<WorkflowHealingLogEntity> findByWorkflowIdOrderByDivergenceSequenceNumberAsc(String workflowId);

    long countByResolutionType(String resolutionType);

    @Query("select count(distinct h.workflowId) from WorkflowHealingLogEntity h")
    long countHealedWorkflows();

    List<WorkflowHealingLogEntity> findTop100ByOrderByCreatedAtDesc();
}
