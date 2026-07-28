package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant context-assembly configuration. OFF by default. */
@Entity
@Table(name = "mmu_setting")
public class MmuSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    /**
     * Score evictable history against the current request instead of paging out
     * whatever is oldest.
     */
    @Column(name = "working_set", nullable = false)
    private boolean workingSet;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MmuSettingEntity() {
    }

    public MmuSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isWorkingSet() { return workingSet; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setWorkingSet(boolean v) {
        this.workingSet = v;
        this.updatedAt = Instant.now();
    }
}
