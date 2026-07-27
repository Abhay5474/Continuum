package io.continuum.persistence.repository;

import io.continuum.persistence.entity.PipelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<PipelineEntity, Long> {

    List<PipelineEntity> findByDeveloperIdOrderByNameAsc(String developerId);

    Optional<PipelineEntity> findByIdAndDeveloperId(Long id, String developerId);

    Optional<PipelineEntity> findByDeveloperIdAndName(String developerId, String name);
}
