package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * L3 — one event of a stub's per-entity append-only stream. Materialization
 * folds ONLY this stream (CREATED base + UPDATED deltas), so restoring a page
 * costs O(events-for-this-stub), never a whole-history replay.
 */
@Entity
@Table(name = "mmu_stub_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_mmu_stub_seq",
                columnNames = {"stub_id", "seq"}),
        indexes = @Index(name = "idx_mmu_events_stub", columnList = "stub_id, seq"))
public class MmuStubEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stub_id", nullable = false, length = 64)
    private String stubId;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MmuStubEventEntity() {
    }

    public MmuStubEventEntity(String stubId, String developerId, int seq,
                              String eventType, String content) {
        this.stubId = stubId;
        this.developerId = developerId;
        this.seq = seq;
        this.eventType = eventType;
        this.content = content;
    }

    public Long getId() { return id; }
    public String getStubId() { return stubId; }
    public String getDeveloperId() { return developerId; }
    public int getSeq() { return seq; }
    public String getEventType() { return eventType; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
