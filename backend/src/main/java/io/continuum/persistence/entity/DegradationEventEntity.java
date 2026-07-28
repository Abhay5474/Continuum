package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One time the ladder was descended. */
@Entity
@Table(name = "degradation_event",
        indexes = @Index(name = "idx_degradation_dev", columnList = "developer_id,created_at"))
public class DegradationEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "rung", nullable = false, length = 16)
    private String rung;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DegradationEventEntity() {
    }

    public DegradationEventEntity(String developerId, String rung, String reason) {
        this.developerId = developerId;
        this.rung = rung;
        this.reason = reason == null ? null : reason.substring(0, Math.min(500, reason.length()));
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getRung() { return rung; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
