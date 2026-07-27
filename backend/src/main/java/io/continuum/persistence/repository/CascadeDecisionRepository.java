package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CascadeDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CascadeDecisionRepository extends JpaRepository<CascadeDecisionEntity, Long> {

    @Query("select d from CascadeDecisionEntity d where d.developerId = :dev order by d.createdAt desc")
    List<CascadeDecisionEntity> recentFor(@Param("dev") String dev, Pageable page);

    void deleteByDeveloperId(String developerId);
}
