package io.continuum.persistence.repository;

import io.continuum.persistence.entity.LoopEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoopEventRepository extends JpaRepository<LoopEventEntity, Long> {

    List<LoopEventEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable page);

    void deleteByDeveloperId(String developerId);
}
