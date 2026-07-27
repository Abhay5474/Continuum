package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A named path from an application's raw input to a model's answer.
 *
 * <p>The steps are an ordered list of specialist ids stored as JSON rather than
 * a join table. Pipelines are short, the order carries the whole meaning, and
 * maintaining ordinals across a join table is more machinery than this problem
 * deserves.
 *
 * <p>Disabled by default. A pipeline is an endpoint an external application can
 * call; one that exists but has never been checked is a request-time failure
 * waiting for a customer to find.
 */
@Entity
@Table(name = "pipeline",
        uniqueConstraints = @UniqueConstraint(name = "uq_pipeline_name",
                columnNames = {"developer_id", "name"}),
        indexes = @Index(name = "idx_pipeline_dev", columnList = "developer_id"))
public class PipelineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "input_kind", nullable = false, length = 16)
    private String inputKind = "image";

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "steps", nullable = false, columnDefinition = "text")
    private String steps = "[]";

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "runs", nullable = false)
    private long runs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PipelineEntity() {
    }

    public PipelineEntity(String developerId, String name, String description, String inputKind,
                          String systemPrompt, String steps) {
        this.developerId = developerId;
        this.name = name;
        this.description = description;
        this.inputKind = inputKind == null ? "image" : inputKind;
        this.systemPrompt = systemPrompt;
        this.steps = steps == null ? "[]" : steps;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInputKind() { return inputKind; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getSteps() { return steps; }
    public boolean isEnabled() { return enabled; }
    public long getRuns() { return runs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setDescription(String v) { this.description = v; touch(); }
    public void setSystemPrompt(String v) { this.systemPrompt = v; touch(); }
    public void setSteps(String v) { this.steps = v == null ? "[]" : v; touch(); }
    public void setEnabled(boolean v) { this.enabled = v; touch(); }

    public void recordRun() {
        this.runs++;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
