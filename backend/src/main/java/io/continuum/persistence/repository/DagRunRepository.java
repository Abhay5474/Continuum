package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DagRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DagRunRepository extends JpaRepository<DagRunEntity, Long> {

    Optional<DagRunEntity> findByWorkflowId(String workflowId);

    List<DagRunEntity> findTop50ByOrderByCreatedAtDesc();

    List<DagRunEntity> findTop50ByDeveloperIdOrderByCreatedAtDesc(String developerId);
}
