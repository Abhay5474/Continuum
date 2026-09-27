package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ActivityTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ActivityTaskRepository extends JpaRepository<ActivityTaskEntity, Long> {

    /**
     * The task row, locked for the rest of the transaction. Recording an
     * outcome reads the task's state and then changes it; without the lock two
     * workers finishing the same task could both read "still running".
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ActivityTaskEntity t where t.id = :id")
    java.util.Optional<ActivityTaskEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Claim a batch of runnable activity tasks.
     *
     * {@code FOR UPDATE SKIP LOCKED} is the heart of the durable queue: rows
     * already locked by another worker's transaction are skipped instead of
     * blocking, so N workers poll the same table and each pulls a disjoint set
     * of work. Caller must be inside a transaction and immediately flip the
     * returned rows to RUNNING.
     */
    @Query(value = """
            SELECT * FROM activity_tasks
            WHERE status = 'PENDING' AND visible_at <= :now
            ORDER BY visible_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivityTaskEntity> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Recover tasks abandoned by a crashed worker: anything still RUNNING past
     * its visibility timeout is made claimable again.
     */
    @Modifying
    @Query(value = """
            UPDATE activity_tasks
            SET status = 'PENDING', locked_by = NULL, locked_until = NULL, updated_at = :now
            WHERE status = 'RUNNING' AND locked_until < :now
            """, nativeQuery = true)
    int recoverTimedOut(@Param("now") Instant now);

    List<ActivityTaskEntity> findByWorkflowIdOrderBySequenceNumberAsc(String workflowId);
}
