package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** An external developer/tenant onboarded to the Continuum gateway. */
@Entity
@Table(name = "developers")
public class DeveloperEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DeveloperEntity() {
    }

    public DeveloperEntity(String name, String email) {
        this.id = "dev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        this.name = name;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}
