package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ReplayVerificationReportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplayVerificationReportRepository extends JpaRepository<ReplayVerificationReportEntity, Long> {

    List<ReplayVerificationReportEntity> findByWorkflowIdOrderByCommandSeqAsc(String workflowId);

    Page<ReplayVerificationReportEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReplayVerificationReportEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);

    long countByPassed(boolean passed);

    /** Mean replay fidelity for one provider, computed by the database; null when there are no reports. */
    @org.springframework.data.jpa.repository.Query(
            "select avg(r.overallScore) from ReplayVerificationReportEntity r where r.freshProvider = :provider")
    Double meanScoreFor(@org.springframework.data.repository.query.Param("provider") String provider);
}
