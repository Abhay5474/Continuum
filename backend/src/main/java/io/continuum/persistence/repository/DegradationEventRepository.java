package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DegradationEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DegradationEventRepository extends JpaRepository<DegradationEventEntity, Long> {

    List<DegradationEventEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId,
                                                                      Pageable page);

    void deleteByDeveloperId(String developerId);
}
