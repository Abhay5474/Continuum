package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A customer-authored workflow definition. Immutable per version — editing
 * publishes a new version rather than mutating the old one, so an in-flight
 * execution always replays against the exact graph it started with.
 */
@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "spec_json", nullable = false, columnDefinition = "text")
    private String specJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowDefinitionEntity() {
    }

    public WorkflowDefinitionEntity(String developerId, String name, int version, String specJson) {
        this.developerId = developerId;
        this.name = name;
        this.version = version;
        this.specJson = specJson;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public String getSpecJson() { return specJson; }
    public Instant getCreatedAt() { return createdAt; }
}
