package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotCanaryRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutopilotCanaryRunRepository extends JpaRepository<AutopilotCanaryRunEntity, Long> {

    List<AutopilotCanaryRunEntity> findByDeveloperIdOrderByStartedAtDesc(String developerId);

    List<AutopilotCanaryRunEntity> findByDeveloperIdAndStatus(String developerId, String status);
}
