package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A breaker transition, with the evidence that caused it. */
@Entity
@Table(name = "breaker_event",
        indexes = @Index(name = "idx_breaker_event_dev", columnList = "developer_id, created_at"))
public class BreakerEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "baseline")
    private Double baseline;

    @Column(name = "observed")
    private Double observed;

    @Column(name = "accumulated")
    private Double accumulated;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BreakerEventEntity() {
    }

    public BreakerEventEntity(String developerId, String provider, String model, String kind,
                              Double baseline, Double observed, Double accumulated, String detail) {
        this.developerId = developerId;
        this.provider = provider;
        this.model = model;
        this.kind = kind;
        this.baseline = baseline;
        this.observed = observed;
        this.accumulated = accumulated;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getKind() { return kind; }
    public Double getBaseline() { return baseline; }
    public Double getObserved() { return observed; }
    public Double getAccumulated() { return accumulated; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
