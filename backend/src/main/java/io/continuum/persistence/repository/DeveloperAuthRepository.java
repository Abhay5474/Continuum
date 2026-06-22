package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeveloperAuthRepository extends JpaRepository<DeveloperAuthEntity, String> {
}
