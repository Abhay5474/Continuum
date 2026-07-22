package io.continuum.persistence.repository;

import io.continuum.persistence.entity.FirewallEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FirewallEventRepository extends JpaRepository<FirewallEventEntity, Long> {

    List<FirewallEventEntity> findTop200ByDeveloperIdOrderByCreatedAtDesc(String developerId);

    List<FirewallEventEntity> findTop200ByOrderByCreatedAtDesc();

    long countByDeveloperId(String developerId);
}
