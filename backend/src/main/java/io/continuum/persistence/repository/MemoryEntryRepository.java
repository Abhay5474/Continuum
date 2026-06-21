package io.continuum.persistence.repository;

import io.continuum.memory.MemoryTier;
import io.continuum.persistence.entity.MemoryEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MemoryEntryRepository extends JpaRepository<MemoryEntryEntity, Long> {

    List<MemoryEntryEntity> findByScope(String scope);

    List<MemoryEntryEntity> findByScopeAndTier(String scope, MemoryTier tier);

    long countByScope(String scope);

    List<MemoryEntryEntity> findByScopeAndTierAndCreatedAtBefore(String scope, MemoryTier tier, Instant before);
}
