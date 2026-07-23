package io.continuum.persistence.repository;

import io.continuum.persistence.entity.TeamInviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamInviteRepository extends JpaRepository<TeamInviteEntity, Long> {

    List<TeamInviteEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);

    void deleteByDeveloperId(String developerId);
}
