package io.continuum.persistence.entity;

import io.continuum.autopilot.model.PolicyStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A versioned, content-immutable policy bundle. Each row is one version; history
 * is preserved by never mutating {@code bundleJson}. Only {@code status} moves
 * through the lifecycle, and rollback targets are retained as ARCHIVED/ACTIVE
 * rows.
 */
@Entity
@Table(name = "autopilot_policy_bundles",
        indexes = {
                @Index(name = "idx_apbundle_dev", columnList = "developer_id"),
                @Index(name = "idx_apbundle_status", columnList = "developer_id, status")
        })
public class PolicyBundleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status;

    /** Immutable JSON of the {@link io.continuum.autopilot.model.PolicyBundle}. */
    @Column(name = "bundle_json", columnDefinition = "text", nullable = false, updatable = false)
    private String bundleJson;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "source", length = 40)
    private String source; // SEED | AUTOPILOT | DEVELOPER

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PolicyBundleEntity() {
    }

    public PolicyBundleEntity(String developerId, int version, PolicyStatus status,
                              String bundleJson, Long parentId, String source, String notes) {
        this.developerId = developerId;
        this.version = version;
        this.status = status;
        this.bundleJson = bundleJson;
        this.parentId = parentId;
        this.source = source;
        this.notes = notes;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public int getVersion() { return version; }
    public PolicyStatus getStatus() { return status; }
    public void setStatus(PolicyStatus status) { this.status = status; }
    public String getBundleJson() { return bundleJson; }
    public Long getParentId() { return parentId; }
    public String getSource() { return source; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
