package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** L2 — one semantic stub: a paged-out context segment's in-window reference. */
@Entity
@Table(name = "mmu_semantic_stubs",
        indexes = @Index(name = "idx_mmu_stubs_dev", columnList = "developer_id, created_at"))
public class MmuStubEntity {

    @Id
    @Column(name = "stub_id", length = 64)
    private String stubId;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "source_tokens", nullable = false)
    private int sourceTokens;

    @Column(name = "stub_tokens", nullable = false)
    private int stubTokens;

    @Column(name = "dirty", nullable = false)
    private boolean dirty;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MmuStubEntity() {
    }

    public MmuStubEntity(String stubId, String developerId, String summary,
                         int sourceTokens, int stubTokens) {
        this.stubId = stubId;
        this.developerId = developerId;
        this.summary = summary;
        this.sourceTokens = sourceTokens;
        this.stubTokens = stubTokens;
    }

    public String getStubId() { return stubId; }
    public String getDeveloperId() { return developerId; }
    public String getSummary() { return summary; }
    public void setSummary(String s) { this.summary = s; this.updatedAt = Instant.now(); }
    public int getSourceTokens() { return sourceTokens; }
    public int getStubTokens() { return stubTokens; }
    public boolean isDirty() { return dirty; }
    public void setDirty(boolean d) { this.dirty = d; this.updatedAt = Instant.now(); }
    public int getVersion() { return version; }
    public void bumpVersion() { this.version++; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
