package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ExperienceEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceEdgeRepository extends JpaRepository<ExperienceEdgeEntity, Long> {

    List<ExperienceEdgeEntity> findByDeveloperId(String developerId);

    void deleteByDeveloperId(String developerId);

    void deleteByFromNodeIdOrToNodeId(Long fromNodeId, Long toNodeId);
}
