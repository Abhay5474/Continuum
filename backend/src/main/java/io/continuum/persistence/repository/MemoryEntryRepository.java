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

    /** Every scope under a prefix (an account's "dev::"), with how many memories each holds. */
    @org.springframework.data.jpa.repository.Query(
            "select m.scope, count(m) from MemoryEntryEntity m where substring(m.scope, 1, :len) = :prefix "
                    + "group by m.scope order by count(m) desc")
    List<Object[]> scopesUnder(@org.springframework.data.repository.query.Param("prefix") String prefix,
                               @org.springframework.data.repository.query.Param("len") int len);
}
