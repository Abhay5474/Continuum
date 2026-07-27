package io.continuum.persistence.repository;

import io.continuum.persistence.entity.UncertaintyMeasurementEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UncertaintyMeasurementRepository
        extends JpaRepository<UncertaintyMeasurementEntity, Long> {

    @Query("select m from UncertaintyMeasurementEntity m where m.developerId = :dev "
            + "order by m.createdAt desc")
    List<UncertaintyMeasurementEntity> recentFor(@Param("dev") String dev, Pageable page);

    void deleteByDeveloperId(String developerId);
}
