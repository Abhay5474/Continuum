package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One breaker: a (tenant, provider, model) triple and how it is currently behaving. */
@Entity
@Table(name = "breaker_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_breaker",
                columnNames = {"developer_id", "provider", "model"}),
        indexes = @Index(name = "idx_breaker_state_dev", columnList = "developer_id"))
public class BreakerStateEntity {

    public enum State {
        /** Serving normally. */
        CLOSED,
        /** Quality drifted; traffic is routed away from this model. */
        OPEN,
        /** Cooldown elapsed; a probe is allowed through to see if it recovered. */
        HALF_OPEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state = State.CLOSED;

    @Column(name = "baseline")
    private Double baseline;

    @Column(name = "recent_mean")
    private Double recentMean;

    @Column(name = "accumulated", nullable = false)
    private double accumulated;

    @Column(name = "observations", nullable = false)
    private long observations;

    @Column(name = "trips", nullable = false)
    private int trips;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "last_probe_at")
    private Instant lastProbeAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BreakerStateEntity() {
    }

    public BreakerStateEntity(String developerId, String provider, String model) {
        this.developerId = developerId;
        this.provider = provider;
        this.model = model;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public State getState() { return state; }
    public Double getBaseline() { return baseline; }
    public Double getRecentMean() { return recentMean; }
    public double getAccumulated() { return accumulated; }
    public long getObservations() { return observations; }
    public int getTrips() { return trips; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getLastProbeAt() { return lastProbeAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void snapshot(Double baseline, Double recentMean, double accumulated, long observations) {
        this.baseline = baseline;
        this.recentMean = recentMean;
        this.accumulated = accumulated;
        this.observations = observations;
        this.updatedAt = Instant.now();
    }

    public void open() {
        this.state = State.OPEN;
        this.openedAt = Instant.now();
        this.trips++;
        this.updatedAt = Instant.now();
    }

    public void halfOpen() {
        this.state = State.HALF_OPEN;
        this.lastProbeAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.state = State.CLOSED;
        this.openedAt = null;
        this.accumulated = 0;
        this.updatedAt = Instant.now();
    }
}
