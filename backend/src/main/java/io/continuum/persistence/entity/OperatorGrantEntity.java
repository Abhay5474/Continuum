package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One person allowed to operate the engine: change settings that apply to every tenant. */
@Entity
@Table(name = "operator_grants")
public class OperatorGrantEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    /** Who granted it; null for the first operator, who claimed the setup code. */
    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    protected OperatorGrantEntity() {
    }

    public OperatorGrantEntity(String developerId, String grantedBy) {
        this.developerId = developerId;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public String getDeveloperId() { return developerId; }
    public String getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }
}
