package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Persisted outcome of one semantic replay verification: a historical LLM
 * output compared against a freshly generated one for the same activity. Drift
 * trends over time are derived from these rows.
 */
@Entity
@Table(name = "replay_verification_reports",
        indexes = {
                @Index(name = "idx_rvr_workflow", columnList = "workflow_id"),
                @Index(name = "idx_rvr_created", columnList = "created_at")
        })
public class ReplayVerificationReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "command_seq", nullable = false)
    private long commandSeq;

    @Column(name = "activity_type", nullable = false, length = 200)
    private String activityType;

    @Column(name = "historical_output", columnDefinition = "text")
    private String historicalOutput;

    @Column(name = "fresh_output", columnDefinition = "text")
    private String freshOutput;

    @Column(name = "fresh_provider", length = 40)
    private String freshProvider;

    @Column(name = "similarity_score", nullable = false)
    private double similarityScore;

    @Column(name = "intent_score", nullable = false)
    private double intentScore;

    @Column(name = "tool_score", nullable = false)
    private double toolScore;

    @Column(name = "structured_score", nullable = false)
    private double structuredScore;

    @Column(name = "constraint_score", nullable = false)
    private double constraintScore;

    @Column(name = "overall_score", nullable = false)
    private double overallScore;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "method", length = 40)
    private String method;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReplayVerificationReportEntity() {
    }

    public ReplayVerificationReportEntity(String workflowId, long commandSeq, String activityType,
                                          String historicalOutput, String freshOutput, String freshProvider,
                                          double similarityScore, double intentScore, double toolScore,
                                          double structuredScore, double constraintScore, double overallScore,
                                          boolean passed, String method, String explanation) {
        this.workflowId = workflowId;
        this.commandSeq = commandSeq;
        this.activityType = activityType;
        this.historicalOutput = historicalOutput;
        this.freshOutput = freshOutput;
        this.freshProvider = freshProvider;
        this.similarityScore = similarityScore;
        this.intentScore = intentScore;
        this.toolScore = toolScore;
        this.structuredScore = structuredScore;
        this.constraintScore = constraintScore;
        this.overallScore = overallScore;
        this.passed = passed;
        this.method = method;
        this.explanation = explanation;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public long getCommandSeq() { return commandSeq; }
    public String getActivityType() { return activityType; }
    public String getHistoricalOutput() { return historicalOutput; }
    public String getFreshOutput() { return freshOutput; }
    public String getFreshProvider() { return freshProvider; }
    public double getSimilarityScore() { return similarityScore; }
    public double getIntentScore() { return intentScore; }
    public double getToolScore() { return toolScore; }
    public double getStructuredScore() { return structuredScore; }
    public double getConstraintScore() { return constraintScore; }
    public double getOverallScore() { return overallScore; }
    public boolean isPassed() { return passed; }
    public String getMethod() { return method; }
    public String getExplanation() { return explanation; }
    public Instant getCreatedAt() { return createdAt; }
}
