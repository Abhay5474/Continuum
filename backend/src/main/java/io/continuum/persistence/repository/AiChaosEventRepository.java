package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AiChaosEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AiChaosEventRepository extends JpaRepository<AiChaosEventEntity, Long> {

    Page<AiChaosEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AiChaosEventEntity> findByWorkflowId(String workflowId);

    @Query("select distinct e.workflowId from AiChaosEventEntity e where e.workflowId is not null")
    List<String> distinctAffectedWorkflowIds();

    @Query("select e.failureType as type, count(e) as count from AiChaosEventEntity e group by e.failureType")
    List<TypeCount> countByType();

    interface TypeCount {
        String getType();
        long getCount();
    }
}
