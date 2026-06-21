package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ReplayVerificationReportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplayVerificationReportRepository extends JpaRepository<ReplayVerificationReportEntity, Long> {

    List<ReplayVerificationReportEntity> findByWorkflowIdOrderByCommandSeqAsc(String workflowId);

    Page<ReplayVerificationReportEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByPassed(boolean passed);
}
