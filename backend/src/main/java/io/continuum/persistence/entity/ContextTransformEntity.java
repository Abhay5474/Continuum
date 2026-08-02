package io.continuum.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One recorded context transformation.
 *
 * <p><b>The input is deliberately not stored.</b> A transformed spreadsheet is
 * the developer's data and frequently commercially sensitive; keeping it here
 * would quietly turn an observability table into a second copy of everything
 * they ever sent through Continuum. Counts and shape answer the question this
 * table exists for — is the layer helping — without holding anything that would
 * matter if the table leaked.
 */
@Entity
@Table(name = "context_transform")
public class ContextTransformEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "context_type", nullable = false, length = 32)
    private String contextType;

    @Column(name = "transformer", length = 64)
    private String transformer;

    @Column(name = "source_name", length = 255)
    private String sourceName;

    @Column(name = "input_bytes", nullable = false)
    private int inputBytes;

    @Column(name = "tokens_before", nullable = false)
    private int tokensBefore;

    @Column(name = "tokens_after", nullable = false)
    private int tokensAfter;

    @Column(name = "ambiguities", nullable = false)
    private int ambiguities;

    @Column(name = "structure", length = 512)
    private String structure;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ContextTransformEntity() {
    }

    public ContextTransformEntity(String developerId, String contextType, String transformer,
                                  String sourceName, int inputBytes, int tokensBefore,
                                  int tokensAfter, int ambiguities, String structure) {
        this.developerId = developerId;
        this.contextType = contextType;
        this.transformer = transformer;
        this.sourceName = clip(sourceName, 255);
        this.inputBytes = inputBytes;
        this.tokensBefore = tokensBefore;
        this.tokensAfter = tokensAfter;
        this.ambiguities = ambiguities;
        this.structure = clip(structure, 512);
    }

    private static String clip(String v, int max) {
        return v == null ? null : (v.length() <= max ? v : v.substring(0, max));
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("contextType", contextType);
        m.put("transformer", transformer);
        m.put("sourceName", sourceName);
        m.put("inputBytes", inputBytes);
        m.put("tokensBefore", tokensBefore);
        m.put("tokensAfter", tokensAfter);
        m.put("saved", tokensBefore - tokensAfter);
        m.put("reduction", tokensBefore <= 0 ? 0
                : (tokensBefore - tokensAfter) / (double) tokensBefore);
        m.put("ambiguities", ambiguities);
        m.put("structure", structure);
        m.put("createdAt", createdAt.toString());
        return m;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getContextType() { return contextType; }
    public int getTokensBefore() { return tokensBefore; }
    public int getTokensAfter() { return tokensAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
