package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DagEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DagEdgeRepository extends JpaRepository<DagEdgeEntity, Long> {

    List<DagEdgeEntity> findByWorkflowId(String workflowId);

    void deleteByWorkflowId(String workflowId);
}
