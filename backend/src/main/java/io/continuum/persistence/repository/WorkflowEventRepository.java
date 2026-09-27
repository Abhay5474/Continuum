package io.continuum.persistence.repository;

import io.continuum.persistence.entity.WorkflowEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEventEntity, Long> {

    /** Load a workflow's full history in deterministic order — the basis of replay. */
    List<WorkflowEventEntity> findByWorkflowIdOrderBySequenceNumberAsc(String workflowId);

    long countByWorkflowId(String workflowId);

    /** The events after {@code sequenceNumber}: what a cached history is missing. */
    List<WorkflowEventEntity> findByWorkflowIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            String workflowId, long sequenceNumber);

    /** The row id of one event, found through the (workflow, sequence) unique index. */
    @org.springframework.data.jpa.repository.Query("select e.id from WorkflowEventEntity e "
            + "where e.workflowId = :workflowId and e.sequenceNumber = :seq")
    java.util.Optional<Long> idAt(@org.springframework.data.repository.query.Param("workflowId") String workflowId,
                                  @org.springframework.data.repository.query.Param("seq") long seq);
}
