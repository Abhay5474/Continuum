package io.continuum.persistence.repository;

import io.continuum.autopilot.model.PolicyStatus;
import io.continuum.persistence.entity.PolicyBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyBundleRepository extends JpaRepository<PolicyBundleEntity, Long> {

    List<PolicyBundleEntity> findByDeveloperIdOrderByVersionDesc(String developerId);

    List<PolicyBundleEntity> findByDeveloperIdAndStatus(String developerId, PolicyStatus status);

    long countByDeveloperId(String developerId);
}
