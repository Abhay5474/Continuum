package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DeveloperEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeveloperRepository extends JpaRepository<DeveloperEntity, String> {

    Optional<DeveloperEntity> findFirstByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
