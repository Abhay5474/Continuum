package io.continuum.persistence.repository;

import io.continuum.persistence.entity.RoutingDecisionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingDecisionRepository extends JpaRepository<RoutingDecisionEntity, Long> {

    Page<RoutingDecisionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<RoutingDecisionEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);
}
