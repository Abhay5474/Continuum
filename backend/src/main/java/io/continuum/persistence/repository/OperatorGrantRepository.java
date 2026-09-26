package io.continuum.persistence.repository;

import io.continuum.persistence.entity.OperatorGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorGrantRepository extends JpaRepository<OperatorGrantEntity, String> {
}
