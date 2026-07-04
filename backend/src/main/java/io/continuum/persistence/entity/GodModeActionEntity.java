package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Memory-as-Action audit record: one autonomous memory decision and its reward. */
@Entity
@Table(name = "god_mode_actions",
        indexes = @Index(name = "idx_gm_actions_dev", columnList = "developer_id, created_at"))
public class GodModeActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "tier", length = 16)
    private String tier;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "reward")
    private Double reward;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GodModeActionEntity() {
    }

    public GodModeActionEntity(String developerId, String action, String tier, String detail, Double reward) {
        this.developerId = developerId;
        this.action = action;
        this.tier = tier;
        this.detail = detail;
        this.reward = reward;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getAction() { return action; }
    public String getTier() { return tier; }
    public String getDetail() { return detail; }
    public Double getReward() { return reward; }
    public Instant getCreatedAt() { return createdAt; }
}
