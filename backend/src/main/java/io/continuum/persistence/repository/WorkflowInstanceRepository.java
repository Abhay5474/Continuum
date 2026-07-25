package io.continuum.persistence.repository;

import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.entity.WorkflowStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstanceEntity, String> {

    /**
     * Pessimistic write lock on a single instance row. This is the per-workflow
     * serialization point used by the event store so concurrent decisions and
     * activity completions cannot corrupt the sequence cursor.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowInstanceEntity w where w.workflowId = :id")
    Optional<WorkflowInstanceEntity> findByIdForUpdate(@Param("id") String id);

    Page<WorkflowInstanceEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Tenant-scoped listing for the console. */
    Page<WorkflowInstanceEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);

    Page<WorkflowInstanceEntity> findByStatusOrderByCreatedAtDesc(WorkflowStatus status, Pageable pageable);

    long countByStatus(WorkflowStatus status);

    long countByDeveloperIdAndStatus(String developerId, WorkflowStatus status);
}
