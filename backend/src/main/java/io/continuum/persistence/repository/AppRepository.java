package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AppEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppRepository extends JpaRepository<AppEntity, Long> {

    List<AppEntity> findByDeveloperIdOrderByCreatedAtAsc(String developerId);

    Optional<AppEntity> findByIdAndDeveloperId(Long id, String developerId);

    long countByDeveloperId(String developerId);
}
