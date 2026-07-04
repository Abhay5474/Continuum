package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MemoryWorkingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MemoryWorkingRepository extends JpaRepository<MemoryWorkingEntity, Long> {

    List<MemoryWorkingEntity> findByDeveloperIdOrderByCreatedAtAsc(String developerId);

    List<MemoryWorkingEntity> findByDeveloperIdAndSessionIdOrderByCreatedAtAsc(String developerId, String sessionId);

    long countByDeveloperId(String developerId);

    @Query("select coalesce(sum(m.tokens), 0) from MemoryWorkingEntity m where m.developerId = :dev")
    long totalTokens(@Param("dev") String developerId);

    @Modifying
    @Query("delete from MemoryWorkingEntity m where m.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    void deleteByDeveloperId(String developerId);
}
