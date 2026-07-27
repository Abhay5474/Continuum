package io.continuum.persistence.repository;

import io.continuum.persistence.entity.BreakerEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BreakerEventRepository extends JpaRepository<BreakerEventEntity, Long> {

    @Query("select e from BreakerEventEntity e where e.developerId = :dev order by e.createdAt desc")
    List<BreakerEventEntity> recentFor(@Param("dev") String dev, Pageable page);

    void deleteByDeveloperId(String developerId);
}
