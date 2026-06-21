package io.continuum.persistence.repository;

import io.continuum.persistence.entity.WorkflowEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEventEntity, Long> {

    /** Load a workflow's full history in deterministic order — the basis of replay. */
    List<WorkflowEventEntity> findByWorkflowIdOrderBySequenceNumberAsc(String workflowId);

    long countByWorkflowId(String workflowId);
}
