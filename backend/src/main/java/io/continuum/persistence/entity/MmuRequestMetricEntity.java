package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Profiler telemetry for one MMU-processed request. */
@Entity
@Table(name = "mmu_request_metrics",
        indexes = @Index(name = "idx_mmu_metrics_dev", columnList = "developer_id, created_at"))
public class MmuRequestMetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "tokens_without_mmu", nullable = false)
    private int tokensWithoutMmu;

    @Column(name = "tokens_sent", nullable = false)
    private int tokensSent;

    @Column(name = "stubs_active", nullable = false)
    private int stubsActive;

    @Column(name = "stubs_created", nullable = false)
    private int stubsCreated;

    @Column(name = "prefetches", nullable = false)
    private int prefetches;

    @Column(name = "page_faults", nullable = false)
    private int pageFaults;

    @Column(name = "fault_latency_ms", nullable = false)
    private long faultLatencyMs;

    @Column(name = "materialization_ms", nullable = false)
    private long materializationMs;

    @Column(name = "dirty_flushes", nullable = false)
    private int dirtyFlushes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MmuRequestMetricEntity() {
    }

    public MmuRequestMetricEntity(String developerId, int tokensWithoutMmu, int tokensSent,
                                  int stubsActive, int stubsCreated, int prefetches,
                                  int pageFaults, long faultLatencyMs, long materializationMs,
                                  int dirtyFlushes) {
        this.developerId = developerId;
        this.tokensWithoutMmu = tokensWithoutMmu;
        this.tokensSent = tokensSent;
        this.stubsActive = stubsActive;
        this.stubsCreated = stubsCreated;
        this.prefetches = prefetches;
        this.pageFaults = pageFaults;
        this.faultLatencyMs = faultLatencyMs;
        this.materializationMs = materializationMs;
        this.dirtyFlushes = dirtyFlushes;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public int getTokensWithoutMmu() { return tokensWithoutMmu; }
    public int getTokensSent() { return tokensSent; }
    public int getStubsActive() { return stubsActive; }
    public int getStubsCreated() { return stubsCreated; }
    public int getPrefetches() { return prefetches; }
    public int getPageFaults() { return pageFaults; }
    public long getFaultLatencyMs() { return faultLatencyMs; }
    public long getMaterializationMs() { return materializationMs; }
    public int getDirtyFlushes() { return dirtyFlushes; }
    public Instant getCreatedAt() { return createdAt; }
}
