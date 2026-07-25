package io.continuum.persistence.repository;

import io.continuum.persistence.entity.WorkflowDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, Long> {

    Optional<WorkflowDefinitionEntity> findFirstByDeveloperIdAndNameOrderByVersionDesc(String developerId, String name);

    Optional<WorkflowDefinitionEntity> findByDeveloperIdAndNameAndVersion(String developerId, String name, int version);

    List<WorkflowDefinitionEntity> findByDeveloperIdOrderByNameAscVersionDesc(String developerId);

    void deleteByDeveloperIdAndName(String developerId, String name);
}
