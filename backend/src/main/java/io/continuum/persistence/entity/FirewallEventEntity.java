package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One firewall detection (redaction, flag, or block). */
@Entity
@Table(name = "firewall_events",
        indexes = @Index(name = "idx_firewall_dev", columnList = "developer_id, created_at"))
public class FirewallEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FirewallEventEntity() {
    }

    public FirewallEventEntity(String developerId, String direction, String category,
                               String action, int matchCount, String detail) {
        this.developerId = developerId;
        this.direction = direction;
        this.category = category;
        this.action = action;
        this.matchCount = matchCount;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getDirection() { return direction; }
    public String getCategory() { return category; }
    public String getAction() { return action; }
    public int getMatchCount() { return matchCount; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
