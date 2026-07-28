package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One recorded decision. */
@Entity
@Table(name = "decision_record",
        indexes = {@Index(name = "idx_decision_dev", columnList = "developer_id,created_at"),
                   @Index(name = "idx_decision_request", columnList = "request_id,seq")})
public class DecisionRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "stage", nullable = false, length = 24)
    private String stage;

    @Column(name = "choice", length = 200)
    private String choice;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "alternatives", length = 500)
    private String alternatives;

    @Column(name = "cost_delta", nullable = false)
    private double costDelta;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DecisionRecordEntity() {
    }

    public DecisionRecordEntity(String developerId, String requestId, int seq, String stage,
                                String choice, String reason, String alternatives,
                                double costDelta, long latencyMs) {
        this.developerId = developerId;
        this.requestId = requestId;
        this.seq = seq;
        this.stage = stage;
        this.choice = clip(choice, 200);
        this.reason = clip(reason, 500);
        this.alternatives = clip(alternatives, 500);
        this.costDelta = costDelta;
        this.latencyMs = latencyMs;
    }

    private static String clip(String v, int max) {
        return v == null ? null : v.substring(0, Math.min(max, v.length()));
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getRequestId() { return requestId; }
    public int getSeq() { return seq; }
    public String getStage() { return stage; }
    public String getChoice() { return choice; }
    public String getReason() { return reason; }
    public String getAlternatives() { return alternatives; }
    public double getCostDelta() { return costDelta; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
}
