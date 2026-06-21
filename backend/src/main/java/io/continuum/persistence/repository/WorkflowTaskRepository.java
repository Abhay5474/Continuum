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
}
