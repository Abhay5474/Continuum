package io.continuum.persistence.repository;

import io.continuum.persistence.entity.SemanticCacheEntryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SemanticCacheEntryRepository extends JpaRepository<SemanticCacheEntryEntity, Long> {

    /**
     * Live candidates for one tenant, newest first.
     *
     * <p>Bounded by the caller: matching compares the incoming prompt against
     * every candidate, so an unbounded scan would make a cache lookup more
     * expensive than the call it is meant to avoid.
     */
    @Query("select e from SemanticCacheEntryEntity e where e.developerId = :dev and e.expiresAt > :now "
            + "order by e.createdAt desc")
    List<SemanticCacheEntryEntity> liveFor(@Param("dev") String dev, @Param("now") Instant now, Pageable page);

    @Query("select e from SemanticCacheEntryEntity e where e.developerId = :dev and e.promptHash = :hash "
            + "and e.expiresAt > :now order by e.createdAt desc")
    List<SemanticCacheEntryEntity> byHash(@Param("dev") String dev, @Param("hash") String hash,
                                          @Param("now") Instant now, Pageable page);

    long countByDeveloperId(String developerId);

    void deleteByDeveloperId(String developerId);

    @Modifying
    @Query("delete from SemanticCacheEntryEntity e where e.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
