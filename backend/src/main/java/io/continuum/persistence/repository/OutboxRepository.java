package io.continuum.persistence.repository;

import io.continuum.persistence.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING' AND visible_at <= :now
            ORDER BY visible_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntity> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    Optional<OutboxEntity> findByIdempotencyKey(String idempotencyKey);

    List<OutboxEntity> findByWorkflowIdOrderByCreatedAtAsc(String workflowId);

    /** Messages delivered on behalf of one account's runs. */
    @Query(value = """
            SELECT count(*) FROM outbox o
            JOIN workflow_instances w ON w.workflow_id = o.workflow_id
            WHERE w.developer_id = :dev AND o.status = 'SENT'
            """, nativeQuery = true)
    long countSentForDeveloper(@Param("dev") String dev);
}
