package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AutopilotDecisionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotDecisionRepository extends JpaRepository<AutopilotDecisionEntity, Long> {

    Page<AutopilotDecisionEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);

    /** Keeps the newest {@code keep} rows for a developer; the loop writes one every cycle. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(value = "delete from autopilot_decisions where developer_id = :dev and id not in "
            + "(select id from autopilot_decisions where developer_id = :dev order by id desc limit :keep)", nativeQuery = true)
    int pruneTo(@org.springframework.data.repository.query.Param("dev") String developerId,
                @org.springframework.data.repository.query.Param("keep") int keep);
}
