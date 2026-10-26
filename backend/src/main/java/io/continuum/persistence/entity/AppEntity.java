package io.continuum.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One application an account runs through Continuum.
 *
 * <p>Every API key belongs to an app, and everything captured for a request
 * made with that key is tagged with it. Keys rather than apps would be simpler,
 * but a key gets replaced — leaked, rotated — and the app's history, memory and
 * cache must survive that. A replacement key joins the same app.
 */
@Entity
@Table(name = "apps")
public class AppEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppEntity() {
    }

    public AppEntity(String developerId, String name) {
        this.developerId = developerId;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreatedAt() { return createdAt; }
}
