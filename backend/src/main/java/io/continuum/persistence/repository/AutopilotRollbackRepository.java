package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotRollbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutopilotRollbackRepository extends JpaRepository<AutopilotRollbackEntity, Long> {

    List<AutopilotRollbackEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);
}
