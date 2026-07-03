package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotRecommendationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutopilotRecommendationRepository extends JpaRepository<AutopilotRecommendationEntity, Long> {

    List<AutopilotRecommendationEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);

    List<AutopilotRecommendationEntity> findByDeveloperIdAndStatus(String developerId, String status);
}
