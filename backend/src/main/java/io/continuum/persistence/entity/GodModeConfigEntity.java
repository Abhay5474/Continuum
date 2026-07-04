package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-developer God Mode opt-in state and memory/twin budgets.
 * OFF by default; while off, every God Mode hook is a strict no-op.
 */
@Entity
@Table(name = "god_mode_config")
public class GodModeConfigEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "memact_enabled", nullable = false)
    private boolean memactEnabled = true;

    @Column(name = "twin_gate_enabled", nullable = false)
    private boolean twinGateEnabled = true;

    @Column(name = "context_budget_tokens", nullable = false)
    private int contextBudgetTokens = 8000;

    @Column(name = "working_ttl_minutes", nullable = false)
    private int workingTtlMinutes = 120;

    @Column(name = "episodic_ttl_days", nullable = false)
    private int episodicTtlDays = 14;

    @Column(name = "semantic_ttl_days", nullable = false)
    private int semanticTtlDays = 90;

    @Column(name = "max_working_items", nullable = false)
    private int maxWorkingItems = 500;

    @Column(name = "max_episodic_items", nullable = false)
    private int maxEpisodicItems = 2000;

    @Column(name = "max_semantic_nodes", nullable = false)
    private int maxSemanticNodes = 5000;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected GodModeConfigEntity() {
    }

    public GodModeConfigEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; this.updatedAt = Instant.now(); }
    public boolean isMemactEnabled() { return memactEnabled; }
    public void setMemactEnabled(boolean v) { this.memactEnabled = v; this.updatedAt = Instant.now(); }
    public boolean isTwinGateEnabled() { return twinGateEnabled; }
    public void setTwinGateEnabled(boolean v) { this.twinGateEnabled = v; this.updatedAt = Instant.now(); }
    public int getContextBudgetTokens() { return contextBudgetTokens; }
    public void setContextBudgetTokens(int v) { this.contextBudgetTokens = v; this.updatedAt = Instant.now(); }
    public int getWorkingTtlMinutes() { return workingTtlMinutes; }
    public void setWorkingTtlMinutes(int v) { this.workingTtlMinutes = v; }
    public int getEpisodicTtlDays() { return episodicTtlDays; }
    public void setEpisodicTtlDays(int v) { this.episodicTtlDays = v; }
    public int getSemanticTtlDays() { return semanticTtlDays; }
    public void setSemanticTtlDays(int v) { this.semanticTtlDays = v; }
    public int getMaxWorkingItems() { return maxWorkingItems; }
    public int getMaxEpisodicItems() { return maxEpisodicItems; }
    public int getMaxSemanticNodes() { return maxSemanticNodes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
