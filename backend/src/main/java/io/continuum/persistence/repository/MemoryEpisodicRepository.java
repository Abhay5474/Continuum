package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MemoryEpisodicEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MemoryEpisodicRepository extends JpaRepository<MemoryEpisodicEntity, Long> {

    List<MemoryEpisodicEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId);

    long countByDeveloperId(String developerId);

    @Modifying
    @Query("delete from MemoryEpisodicEntity m where m.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    void deleteByDeveloperId(String developerId);
}
