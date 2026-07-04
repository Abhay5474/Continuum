package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Tier 4 — cold archive: heavily compressed spans of old memory. */
@Entity
@Table(name = "memory_archives",
        indexes = @Index(name = "idx_mem_archives_dev", columnList = "developer_id"))
public class MemoryArchiveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "span_label", length = 200)
    private String spanLabel;

    @Column(name = "archive_text", nullable = false, columnDefinition = "text")
    private String archiveText;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MemoryArchiveEntity() {
    }

    public MemoryArchiveEntity(String developerId, String spanLabel, String archiveText, int itemCount) {
        this.developerId = developerId;
        this.spanLabel = spanLabel;
        this.archiveText = archiveText;
        this.itemCount = itemCount;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getSpanLabel() { return spanLabel; }
    public String getArchiveText() { return archiveText; }
    public int getItemCount() { return itemCount; }
    public Instant getCreatedAt() { return createdAt; }
}
