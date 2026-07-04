package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One digital-twin counterfactual simulation run (offline policy replay). */
@Entity
@Table(name = "god_mode_simulations",
        indexes = @Index(name = "idx_gm_sim_dev", columnList = "developer_id"))
public class GodModeSimulationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "candidate_bundle_id")
    private Long candidateBundleId;

    @Column(name = "scenario", nullable = false, length = 64)
    private String scenario = "HISTORICAL_REPLAY";

    @Column(name = "replayed_requests", nullable = false)
    private int replayedRequests;

    @Column(name = "baseline_metrics_json", columnDefinition = "text")
    private String baselineMetricsJson;

    @Column(name = "candidate_metrics_json", columnDefinition = "text")
    private String candidateMetricsJson;

    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GodModeSimulationEntity() {
    }

    public GodModeSimulationEntity(String developerId, Long candidateBundleId, String scenario,
                                   int replayedRequests, String baselineMetricsJson,
                                   String candidateMetricsJson, String verdict, String reason,
                                   double confidence) {
        this.developerId = developerId;
        this.candidateBundleId = candidateBundleId;
        this.scenario = scenario;
        this.replayedRequests = replayedRequests;
        this.baselineMetricsJson = baselineMetricsJson;
        this.candidateMetricsJson = candidateMetricsJson;
        this.verdict = verdict;
        this.reason = reason;
        this.confidence = confidence;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public Long getCandidateBundleId() { return candidateBundleId; }
    public String getScenario() { return scenario; }
    public int getReplayedRequests() { return replayedRequests; }
    public String getBaselineMetricsJson() { return baselineMetricsJson; }
    public String getCandidateMetricsJson() { return candidateMetricsJson; }
    public String getVerdict() { return verdict; }
    public String getReason() { return reason; }
    public double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}
