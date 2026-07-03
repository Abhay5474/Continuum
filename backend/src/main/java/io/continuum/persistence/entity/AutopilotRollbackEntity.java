package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Record of an automatic or manual rollback to a known-good bundle. */
@Entity
@Table(name = "autopilot_rollbacks",
        indexes = @Index(name = "idx_aprollback_dev", columnList = "developer_id, created_at"))
public class AutopilotRollbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "from_bundle_id")
    private Long fromBundleId;

    @Column(name = "to_bundle_id")
    private Long toBundleId;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "automatic", nullable = false)
    private boolean automatic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AutopilotRollbackEntity() {
    }

    public AutopilotRollbackEntity(String developerId, Long fromBundleId, Long toBundleId,
                                   String reason, boolean automatic) {
        this.developerId = developerId;
        this.fromBundleId = fromBundleId;
        this.toBundleId = toBundleId;
        this.reason = reason;
        this.automatic = automatic;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public Long getFromBundleId() { return fromBundleId; }
    public Long getToBundleId() { return toBundleId; }
    public String getReason() { return reason; }
    public boolean isAutomatic() { return automatic; }
    public Instant getCreatedAt() { return createdAt; }
}
