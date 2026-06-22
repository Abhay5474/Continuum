package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DeveloperEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeveloperRepository extends JpaRepository<DeveloperEntity, String> {
}
