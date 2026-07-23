package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DeveloperBillingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeveloperBillingRepository extends JpaRepository<DeveloperBillingEntity, String> {
}
