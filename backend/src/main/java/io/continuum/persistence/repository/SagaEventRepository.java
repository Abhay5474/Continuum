package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SagaEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaEventRepository extends JpaRepository<SagaEventEntity, Long> {

    List<SagaEventEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable page);

    void deleteByDeveloperId(String developerId);
}
