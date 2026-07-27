package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A configured model on a connection, and what a probe learned about it.
 *
 * <p>The probe fields are not diagnostics. They are the configuration: nobody
 * can tell you the shape of a third-party model's response reliably, so
 * Continuum sends one real request and keeps the answer. The console shows that
 * answer rather than describing it, which is the difference between a developer
 * trusting the integration and hoping.
 */
@Entity
@Table(name = "specialist",
        uniqueConstraints = @UniqueConstraint(name = "uq_specialist_name",
                columnNames = {"developer_id", "name"}),
        indexes = @Index(name = "idx_specialist_dev", columnList = "developer_id"))
public class SpecialistEntity {

    public enum Status {
        /** Never proved to work; cannot be used. */
        DRAFT,
        /** A probe succeeded and returned something parseable. */
        READY,
        /** A probe reached the endpoint but nothing could be parsed from it. */
        UNPARSEABLE,
        /** The probe failed outright. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "model_path", nullable = false, length = 300)
    private String modelPath;

    @Column(name = "input_kind", nullable = false, length = 16)
    private String inputKind = "image";

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence = 0.30;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 20;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "probe_response", columnDefinition = "text")
    private String probeResponse;

    @Column(name = "probe_findings", columnDefinition = "text")
    private String probeFindings;

    @Column(name = "probe_status")
    private Integer probeStatus;

    @Column(name = "probe_ms")
    private Long probeMs;

    @Column(name = "probe_error", columnDefinition = "text")
    private String probeError;

    @Column(name = "probed_at")
    private Instant probedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SpecialistEntity() {
    }

    public SpecialistEntity(String developerId, Long connectionId, String name, String modelPath,
                            String inputKind, double minConfidence, int timeoutSeconds) {
        this.developerId = developerId;
        this.connectionId = connectionId;
        this.name = name;
        this.modelPath = modelPath;
        this.inputKind = inputKind == null ? "image" : inputKind;
        this.minConfidence = clampConfidence(minConfidence);
        this.timeoutSeconds = clampTimeout(timeoutSeconds);
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public Long getConnectionId() { return connectionId; }
    public String getName() { return name; }
    public String getModelPath() { return modelPath; }
    public String getInputKind() { return inputKind; }
    public double getMinConfidence() { return minConfidence; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public Status getStatus() { return status; }
    public String getProbeResponse() { return probeResponse; }
    public String getProbeFindings() { return probeFindings; }
    public Integer getProbeStatus() { return probeStatus; }
    public Long getProbeMs() { return probeMs; }
    public String getProbeError() { return probeError; }
    public Instant getProbedAt() { return probedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMinConfidence(double v) {
        this.minConfidence = clampConfidence(v);
        this.updatedAt = Instant.now();
    }

    public void setTimeoutSeconds(int v) {
        this.timeoutSeconds = clampTimeout(v);
        this.updatedAt = Instant.now();
    }

    /**
     * Records a probe. {@code UNPARSEABLE} is a distinct outcome from
     * {@code FAILED} on purpose: the endpoint answered, so the credential and
     * the path are right and the adapter is what needs attention.
     */
    public void recordProbe(int httpStatus, long ms, String rawResponse, String findingsJson,
                            int findingCount, String error) {
        this.probeStatus = httpStatus;
        this.probeMs = ms;
        this.probeResponse = truncate(rawResponse);
        this.probeFindings = findingsJson;
        this.probeError = truncate(error);
        this.probedAt = Instant.now();
        this.updatedAt = Instant.now();
        if (error != null) {
            this.status = Status.FAILED;
        } else if (findingCount > 0) {
            this.status = Status.READY;
        } else {
            this.status = Status.UNPARSEABLE;
        }
    }

    private static double clampConfidence(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Capped: a specialist sits in front of a live request, so it cannot stall it. */
    private static int clampTimeout(int v) {
        return Math.max(1, Math.min(60, v));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 8000 ? s : s.substring(0, 8000) + "…";
    }
}
