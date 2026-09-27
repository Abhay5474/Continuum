package io.continuum.persistence.repository;

import io.continuum.persistence.entity.WorkflowTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WorkflowTaskRepository extends JpaRepository<WorkflowTaskEntity, Long> {

    @Query(value = """
            SELECT * FROM workflow_tasks
            WHERE status = 'PENDING' AND visible_at <= :now
            ORDER BY visible_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WorkflowTaskEntity> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE workflow_tasks
            SET status = 'PENDING', locked_by = NULL, locked_until = NULL
            WHERE status = 'RUNNING' AND locked_until < :now
            """, nativeQuery = true)
    int recoverTimedOut(@Param("now") Instant now);

    /** The parked decision of a workflow, if it has one: the newest FAILED task. */
    java.util.Optional<WorkflowTaskEntity> findFirstByWorkflowIdAndStatusOrderByUpdatedAtDesc(
            String workflowId, io.continuum.persistence.entity.TaskStatus status);

    /** Running workflows with a parked decision, among the given ones. */
    @Query(value = """
            SELECT DISTINCT t.workflow_id FROM workflow_tasks t
            JOIN workflow_instances w ON w.workflow_id = t.workflow_id
            WHERE t.status = 'FAILED' AND w.status = 'RUNNING' AND t.workflow_id IN (:ids)
            """, nativeQuery = true)
    List<String> stuckAmong(@Param("ids") java.util.Collection<String> ids);

    @Query(value = """
            SELECT count(DISTINCT t.workflow_id) FROM workflow_tasks t
            JOIN workflow_instances w ON w.workflow_id = t.workflow_id
            WHERE t.status = 'FAILED' AND w.status = 'RUNNING'
            """, nativeQuery = true)
    long countStuck();

    /** A later decision went through (or the workflow was resumed): its parked ones are history. */
    @Modifying
    @Query(value = "UPDATE workflow_tasks SET status = 'COMPLETED', updated_at = now() "
            + "WHERE workflow_id = :id AND status = 'FAILED'", nativeQuery = true)
    int clearParked(@Param("id") String workflowId);
}
