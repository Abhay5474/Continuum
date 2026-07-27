package io.continuum.persistence.repository;

import io.continuum.persistence.entity.TraceStepEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TraceStepRepository extends JpaRepository<TraceStepEntity, Long> {

    List<TraceStepEntity> findByTraceIdAndDeveloperIdOrderByOrdinalAsc(String traceId, String developerId);

    // Grouped explicitly: ordering by an aggregate without a GROUP BY parses
    // but fails at execution, which would only surface the first time anyone
    // opened the trace list.
    @Query("select t.traceId from TraceStepEntity t where t.developerId = :dev "
            + "group by t.traceId order by max(t.createdAt) desc")
    List<String> recentTraceIds(@Param("dev") String dev, Pageable page);

    void deleteByDeveloperId(String developerId);
}
