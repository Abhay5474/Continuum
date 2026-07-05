package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MmuStubEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MmuStubRepository extends JpaRepository<MmuStubEntity, String> {

    List<MmuStubEntity> findTop100ByDeveloperIdOrderByUpdatedAtDesc(String developerId);

    long countByDeveloperId(String developerId);
}
