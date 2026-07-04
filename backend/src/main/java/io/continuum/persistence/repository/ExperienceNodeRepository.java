package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ExperienceNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ExperienceNodeRepository extends JpaRepository<ExperienceNodeEntity, Long> {

    List<ExperienceNodeEntity> findByDeveloperIdOrderByUtilityScoreDesc(String developerId);

    long countByDeveloperId(String developerId);

    @Query("select n from ExperienceNodeEntity n where n.developerId = :dev and n.expiresAt < :now")
    List<ExperienceNodeEntity> expiredFor(@Param("dev") String developerId, @Param("now") Instant now);

    @Modifying
    @Query("delete from ExperienceNodeEntity n where n.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    void deleteByDeveloperId(String developerId);
}
