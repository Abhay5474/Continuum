package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A canary comparison between a candidate bundle and the active baseline. */
@Entity
@Table(name = "autopilot_canary_runs",
        indexes = @Index(name = "idx_apcanary_dev", columnList = "developer_id, status"))
public class AutopilotCanaryRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "candidate_bundle_id", nullable = false)
    private Long candidateBundleId;

    @Column(name = "baseline_bundle_id")
    private Long baselineBundleId;

    @Column(name = "percentage", nullable = false)
    private int percentage;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "RUNNING"; // RUNNING | PROMOTED | ROLLED_BACK | INCONCLUSIVE

    @Column(name = "metrics_json", columnDefinition = "text")
    private String metricsJson;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    protected AutopilotCanaryRunEntity() {
    }

    public AutopilotCanaryRunEntity(String developerId, Long candidateBundleId, Long baselineBundleId, int percentage) {
        this.developerId = developerId;
        this.candidateBundleId = candidateBundleId;
        this.baselineBundleId = baselineBundleId;
        this.percentage = percentage;
        this.startedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public Long getCandidateBundleId() { return candidateBundleId; }
    public Long getBaselineBundleId() { return baselineBundleId; }
    public int getPercentage() { return percentage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
