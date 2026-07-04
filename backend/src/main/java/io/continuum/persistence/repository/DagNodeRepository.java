package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DagNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DagNodeRepository extends JpaRepository<DagNodeEntity, Long> {

    List<DagNodeEntity> findByWorkflowIdOrderByIdAsc(String workflowId);

    void deleteByWorkflowId(String workflowId);
}
