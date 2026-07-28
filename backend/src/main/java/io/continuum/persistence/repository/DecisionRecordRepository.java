package io.continuum.persistence.repository;

import io.continuum.persistence.entity.DecisionRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecordEntity, Long> {

    List<DecisionRecordEntity> findByRequestIdAndDeveloperIdOrderBySeqAsc(String requestId,
                                                                         String developerId);

    List<DecisionRecordEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId,
                                                                    Pageable page);

    /** Distinct requests, newest first — the index into the provenance graph. */
    @Query("select d.requestId from DecisionRecordEntity d where d.developerId = :dev "
            + "group by d.requestId order by max(d.createdAt) desc")
    List<String> recentRequests(@Param("dev") String dev, Pageable page);

    void deleteByDeveloperId(String developerId);
}
