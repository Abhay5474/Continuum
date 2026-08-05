package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Portal sign-in credentials and per-developer routing preference. Kept in its
 * own table so the existing {@code developers} table is not altered.
 */
@Entity
@Table(name = "developer_auth")
public class DeveloperAuthEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    /**
     * When true (default), the gateway prefers this developer's own provider keys
     * and falls back to the platform/mock only if they fail. When false, requests
     * run on the platform's global keys.
     */
    @Column(name = "use_own_keys_primary", nullable = false)
    private boolean useOwnKeysPrimary = true;

    /**
     * V6 Consensus DAG Engine toggle. OFF by default: while false, gateway
     * requests run the exact legacy path — no DAG is ever compiled.
     */
    @Column(name = "v6_dag_enabled", nullable = false)
    private boolean v6DagEnabled = false;

    /**
     * V7 Context Virtualization (Paging MMU) toggle. OFF by default: while
     * false, the full prompt array is passed to the model exactly as before.
     */
    @Column(name = "v7_mmu_enabled", nullable = false)
    private boolean v7MmuEnabled = false;

    /**
     * V8 Prompt Compression (LLMLingua-inspired) toggle. OFF by default: while
     * false, the prompt is sent verbatim, exactly as before.
     */
    @Column(name = "v8_compression_enabled", nullable = false)
    private boolean v8CompressionEnabled = false;

    /**
     * V8 Prompt Firewall (PII redaction + injection defense) toggle. OFF by
     * default: while false, no scanning or redaction happens.
     */
    @Column(name = "v8_firewall_enabled", nullable = false)
    private boolean v8FirewallEnabled = false;

    /**
     * Context transformation inside gateway messages. OFF by default: while
     * false, a spreadsheet or log pasted into a chat message is forwarded to the
     * provider exactly as the caller wrote it.
     *
     * <p>Separate from the pipeline path, which transforms unconditionally. A
     * pipeline is a thing the developer configured and can watch run; a chat
     * completion is an API call whose prompt they built themselves, and
     * rewriting it without being asked would change an answer they are already
     * depending on.
     */
    @Column(name = "context_transform_enabled", nullable = false)
    private boolean contextTransformEnabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DeveloperAuthEntity() {
    }

    public DeveloperAuthEntity(String developerId, String passwordHash) {
        this.developerId = developerId;
        this.passwordHash = passwordHash;
        this.useOwnKeysPrimary = true;
        this.createdAt = Instant.now();
    }

    public String getDeveloperId() { return developerId; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isUseOwnKeysPrimary() { return useOwnKeysPrimary; }
    public void setUseOwnKeysPrimary(boolean v) { this.useOwnKeysPrimary = v; }
    public boolean isV6DagEnabled() { return v6DagEnabled; }
    public void setV6DagEnabled(boolean v) { this.v6DagEnabled = v; }
    public boolean isV7MmuEnabled() { return v7MmuEnabled; }
    public void setV7MmuEnabled(boolean v) { this.v7MmuEnabled = v; }
    public boolean isV8CompressionEnabled() { return v8CompressionEnabled; }
    public void setV8CompressionEnabled(boolean v) { this.v8CompressionEnabled = v; }
    public boolean isContextTransformEnabled() { return contextTransformEnabled; }
    public void setContextTransformEnabled(boolean v) { this.contextTransformEnabled = v; }
    public boolean isV8FirewallEnabled() { return v8FirewallEnabled; }
    public void setV8FirewallEnabled(boolean v) { this.v8FirewallEnabled = v; }
    public Instant getCreatedAt() { return createdAt; }
}
