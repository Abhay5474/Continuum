package io.continuum.persistence.repository;

import io.continuum.persistence.entity.RoutingStrategyDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoutingStrategyDecisionRepository
        extends JpaRepository<RoutingStrategyDecisionEntity, Long> {

    @Query("select d from RoutingStrategyDecisionEntity d where d.developerId = :dev "
            + "order by d.createdAt desc")
    List<RoutingStrategyDecisionEntity> recentFor(@Param("dev") String dev, Pageable page);

    @Query("select d from RoutingStrategyDecisionEntity d order by d.createdAt desc")
    List<RoutingStrategyDecisionEntity> recentAll(Pageable page);
}
