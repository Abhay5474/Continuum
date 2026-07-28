package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ProvenanceSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvenanceSettingRepository extends JpaRepository<ProvenanceSettingEntity, String> {
}
