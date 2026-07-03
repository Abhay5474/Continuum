package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutopilotFeedbackRepository extends JpaRepository<AutopilotFeedbackEntity, Long> {

    List<AutopilotFeedbackEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);
}
