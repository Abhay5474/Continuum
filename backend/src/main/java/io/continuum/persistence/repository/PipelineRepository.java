package io.continuum.persistence.repository;

import io.continuum.persistence.entity.PipelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<PipelineEntity, Long> {

    List<PipelineEntity> findByDeveloperIdOrderByNameAsc(String developerId);

    Optional<PipelineEntity> findByIdAndDeveloperId(Long id, String developerId);

    Optional<PipelineEntity> findByDeveloperIdAndName(String developerId, String name);

    /**
     * Counts one run in place. Saving the entity loaded at the start of a run
     * would write the whole row back and undo an edit saved while it ran.
     */
    @Modifying
    @Transactional
    @Query("update PipelineEntity p set p.runs = p.runs + 1, p.updatedAt = :at where p.id = :id")
    int recordRun(@Param("id") Long id, @Param("at") Instant at);
}
