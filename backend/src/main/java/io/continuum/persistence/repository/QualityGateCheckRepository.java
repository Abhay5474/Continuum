package io.continuum.persistence.repository;

import io.continuum.persistence.entity.QualityGateCheckEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QualityGateCheckRepository extends JpaRepository<QualityGateCheckEntity, Long> {

    @Query("select c from QualityGateCheckEntity c where c.developerId = :dev order by c.createdAt desc")
    List<QualityGateCheckEntity> recentFor(@Param("dev") String dev, Pageable page);

    void deleteByDeveloperId(String developerId);
}
