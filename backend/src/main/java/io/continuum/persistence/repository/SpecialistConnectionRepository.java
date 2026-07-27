package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecialistConnectionRepository
        extends JpaRepository<SpecialistConnectionEntity, Long> {

    List<SpecialistConnectionEntity> findByDeveloperIdOrderByNameAsc(String developerId);

    Optional<SpecialistConnectionEntity> findByIdAndDeveloperId(Long id, String developerId);

    Optional<SpecialistConnectionEntity> findByDeveloperIdAndName(String developerId, String name);

    void deleteByDeveloperId(String developerId);
}
