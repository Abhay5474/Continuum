package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One visible step in the chain behind an answer.
 *
 * <p>Continuum's contribution is largely invisible without this. An application
 * that sends an image and receives first-aid advice has no way to know that a
 * second model was consulted, that its findings were structured into context,
 * and that the confidence was checked before the advice was written. These rows
 * are what an external app renders to show its own user what happened.
 */
@Entity
@Table(name = "trace_step",
        indexes = {
                @Index(name = "idx_trace_step_trace", columnList = "trace_id, ordinal"),
                @Index(name = "idx_trace_step_dev", columnList = "developer_id, created_at")
        })
public class TraceStepEntity {

    public enum Kind {
        /** POLICY sits between enrichment and the model: it reads how strong the
         *  evidence is and decides what the model is allowed to do with it. */
        INPUT, SPECIALIST, ENRICHMENT, POLICY, MODEL, VERIFY, OUTPUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "OK";

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TraceStepEntity() {
    }

    public TraceStepEntity(String traceId, String developerId, int ordinal, Kind kind, String label,
                           String detail, String status, Double confidence, double cost, long latencyMs) {
        this.traceId = traceId;
        this.developerId = developerId;
        this.ordinal = ordinal;
        this.kind = kind;
        this.label = label;
        this.detail = detail;
        this.status = status == null ? "OK" : status;
        this.confidence = confidence;
        this.cost = cost;
        this.latencyMs = latencyMs;
    }

    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getDeveloperId() { return developerId; }
    public int getOrdinal() { return ordinal; }
    public Kind getKind() { return kind; }
    public String getLabel() { return label; }
    public String getDetail() { return detail; }
    public String getStatus() { return status; }
    public Double getConfidence() { return confidence; }
    public double getCost() { return cost; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
}
