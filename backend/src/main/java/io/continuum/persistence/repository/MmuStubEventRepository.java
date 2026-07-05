package io.continuum.persistence.repository;

import io.continuum.persistence.entity.MmuStubEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MmuStubEventRepository extends JpaRepository<MmuStubEventEntity, Long> {

    List<MmuStubEventEntity> findByStubIdOrderBySeqAsc(String stubId);

    int countByStubId(String stubId);
}
