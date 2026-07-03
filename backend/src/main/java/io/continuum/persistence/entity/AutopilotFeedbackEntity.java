package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Optional developer/user feedback signal fed into the learning loop. */
@Entity
@Table(name = "autopilot_feedback",
        indexes = @Index(name = "idx_apfeedback_dev", columnList = "developer_id, created_at"))
public class AutopilotFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "request_ref", length = 120)
    private String requestRef;

    /** -1 (bad) .. +1 (good). */
    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AutopilotFeedbackEntity() {
    }

    public AutopilotFeedbackEntity(String developerId, String requestRef, double score, String comment) {
        this.developerId = developerId;
        this.requestRef = requestRef;
        this.score = score;
        this.comment = comment;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getRequestRef() { return requestRef; }
    public double getScore() { return score; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
