package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotRequestLabelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AutopilotRequestLabelRepository extends JpaRepository<AutopilotRequestLabelEntity, Long> {

    List<AutopilotRequestLabelEntity> findByBundleIdAndCreatedAtAfter(Long bundleId, Instant after);

    List<AutopilotRequestLabelEntity> findByDeveloperIdAndCreatedAtAfter(String developerId, Instant after);
}
