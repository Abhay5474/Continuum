package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ContextTransformEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContextTransformRepository extends JpaRepository<ContextTransformEntity, Long> {

    List<ContextTransformEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);

    void deleteByDeveloperId(String developerId);
}
