package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Periodic telemetry snapshot the loop observed for a developer. */
@Entity
@Table(name = "autopilot_telemetry",
        indexes = @Index(name = "idx_aptel_dev", columnList = "developer_id, created_at"))
public class AutopilotTelemetryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "snapshot_json", columnDefinition = "text")
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AutopilotTelemetryEntity() {
    }

    public AutopilotTelemetryEntity(String developerId, String snapshotJson) {
        this.developerId = developerId;
        this.snapshotJson = snapshotJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getSnapshotJson() { return snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
}
