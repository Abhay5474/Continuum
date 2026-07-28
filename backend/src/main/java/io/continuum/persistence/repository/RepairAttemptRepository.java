package io.continuum.persistence.repository;

import io.continuum.persistence.entity.RepairAttemptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepairAttemptRepository extends JpaRepository<RepairAttemptEntity, Long> {

    List<RepairAttemptEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable page);

    void deleteByDeveloperId(String developerId);
}
