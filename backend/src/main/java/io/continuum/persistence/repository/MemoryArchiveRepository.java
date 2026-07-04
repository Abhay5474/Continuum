package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MemoryArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryArchiveRepository extends JpaRepository<MemoryArchiveEntity, Long> {

    List<MemoryArchiveEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);

    long countByDeveloperId(String developerId);

    void deleteByDeveloperId(String developerId);
}
