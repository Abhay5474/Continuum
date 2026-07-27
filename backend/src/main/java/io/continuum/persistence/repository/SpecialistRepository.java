package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SpecialistEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecialistRepository extends JpaRepository<SpecialistEntity, Long> {

    List<SpecialistEntity> findByDeveloperIdOrderByNameAsc(String developerId);

    Optional<SpecialistEntity> findByIdAndDeveloperId(Long id, String developerId);

    Optional<SpecialistEntity> findByDeveloperIdAndName(String developerId, String name);

    List<SpecialistEntity> findByConnectionId(Long connectionId);
}
