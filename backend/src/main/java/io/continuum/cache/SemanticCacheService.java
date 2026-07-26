package io.continuum.cache;

import io.continuum.persistence.entity.SemanticCacheEntryEntity;
import io.continuum.persistence.entity.SemanticCacheSettingEntity;
import io.continuum.persistence.repository.SemanticCacheEntryRepository;
import io.continuum.persistence.repository.SemanticCacheSettingRepository;
import io.continuum.semantic.TextVectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Semantic cache — answer a repeated question without paying for it twice.
 *
 * <p>An exact-match cache is nearly useless in front of a language model:
 * "summarise this clause" and "summarize this clause" are the same request and
 * hash differently. So a lookup is two-stage. A digest of the normalised prompt
 * catches the identical case for free; if that misses, the incoming prompt is
 * compared by cosine similarity against the tenant's recent live entries and
 * served only if the best candidate clears the configured threshold.
 *
 * <p>Similarity uses {@link TextVectors}, which is deterministic and needs no
 * embedding provider — so the cache works on a deployment with no API keys at
 * all, and its behaviour is reproducible in a test.
 *
 * <p>Three rules keep it honest:
 * <ul>
 *   <li><b>Tenant-scoped.</b> Every query is filtered by developer id. Serving
 *       one customer another customer's answer would be the worst bug in the
 *       system, so there is no code path that can reach across.</li>
 *   <li><b>Model-scoped.</b> A hit must come from the same model. A cheap
 *       model's answer is not a substitute for an expensive one's.</li>
 *   <li><b>Off by default, and conservative when on.</b> The default threshold
 *       is 0.92. A false hit is worse than a miss because the caller cannot
 *       tell that it happened.</li>
 * </ul>
 *
 * <p>Nothing here may break a request: a cache is an optimisation, so every
 * failure path falls through to calling the provider.
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    /** How many recent entries a similarity scan will consider. */
    private static final int SCAN_LIMIT = 200;

    private final SemanticCacheEntryRepository entries;
    private final SemanticCacheSettingRepository settings;

    public SemanticCacheService(SemanticCacheEntryRepository entries, SemanticCacheSettingRepository settings) {
        this.entries = entries;
        this.settings = settings;
    }

    /** A cached answer, with the score that justified serving it. */
    public record Hit(String response, String provider, String model, int tokens, double cost,
                      double similarity, boolean exact) {
    }

    @Transactional(readOnly = true)
    public boolean enabledFor(String developerId) {
        if (developerId == null) {
            return false;
        }
        return settings.findById(developerId).map(SemanticCacheSettingEntity::isEnabled).orElse(false);
    }

    /**
     * Looks for an answer close enough to serve.
     *
     * <p>Returns empty on anything unexpected — a cache that throws would turn an
     * optimisation into an outage.
     */
    @Transactional
    public Optional<Hit> lookup(String developerId, String prompt, String model) {
        if (developerId == null || prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }
        try {
            SemanticCacheSettingEntity cfg = settings.findById(developerId).orElse(null);
            if (cfg == null || !cfg.isEnabled()) {
                return Optional.empty();
            }
            Instant now = Instant.now();

            // Stage one: the identical prompt, which needs no scoring at all.
            for (SemanticCacheEntryEntity e : entries.byHash(developerId, hash(prompt), now, PageRequest.of(0, 5))) {
                if (sameModel(e.getModel(), model)) {
                    return Optional.of(serve(cfg, e, 1.0, true));
                }
            }

            // Stage two: nearest neighbour above the threshold.
            List<SemanticCacheEntryEntity> candidates =
                    entries.liveFor(developerId, now, PageRequest.of(0, SCAN_LIMIT));
            Map<String, Integer> incoming = TextVectors.termFrequency(prompt);
            SemanticCacheEntryEntity best = null;
            double bestScore = 0;
            for (SemanticCacheEntryEntity e : candidates) {
                if (!sameModel(e.getModel(), model)) {
                    continue;
                }
                double score = TextVectors.cosine(incoming, TextVectors.termFrequency(e.getPromptText()));
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
            if (best != null && bestScore >= cfg.getSimilarityThreshold()) {
                return Optional.of(serve(cfg, best, bestScore, false));
            }

            cfg.recordMiss();
            settings.save(cfg);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Semantic cache lookup failed for {}; calling the provider: {}", developerId, e.getMessage());
            return Optional.empty();
        }
    }

    private Hit serve(SemanticCacheSettingEntity cfg, SemanticCacheEntryEntity e, double score, boolean exact) {
        e.recordHit();
        entries.save(e);
        // What the hit saved is what the original call cost.
        cfg.recordHit(e.getTokens(), e.getCost());
        settings.save(cfg);
        return new Hit(e.getResponse(), e.getProvider(), e.getModel(), e.getTokens(), e.getCost(), score, exact);
    }

    /** Records a fresh answer. Silent no-op when the cache is off. */
    @Transactional
    public void store(String developerId, String prompt, String model, String provider,
                      String response, int tokens, double cost) {
        if (developerId == null || prompt == null || prompt.isBlank() || response == null) {
            return;
        }
        try {
            SemanticCacheSettingEntity cfg = settings.findById(developerId).orElse(null);
            if (cfg == null || !cfg.isEnabled()) {
                return;
            }
            entries.save(new SemanticCacheEntryEntity(developerId, hash(prompt), prompt, model, response,
                    provider, tokens, cost, Instant.now().plusSeconds(cfg.getTtlSeconds())));
        } catch (Exception e) {
            log.warn("Semantic cache store failed for {}: {}", developerId, e.getMessage());
        }
    }

    // --- configuration -------------------------------------------------------

    @Transactional
    public Map<String, Object> setEnabled(String developerId, boolean enabled) {
        SemanticCacheSettingEntity cfg = load(developerId);
        cfg.setEnabled(enabled);
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Double threshold, Integer ttlSeconds) {
        SemanticCacheSettingEntity cfg = load(developerId);
        if (threshold != null) {
            cfg.setSimilarityThreshold(threshold);
        }
        if (ttlSeconds != null) {
            cfg.setTtlSeconds(ttlSeconds);
        }
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        entries.deleteByDeveloperId(developerId);
        SemanticCacheSettingEntity cfg = load(developerId);
        cfg.resetStats();
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        SemanticCacheSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new SemanticCacheSettingEntity(developerId));
        long hits = cfg.getHits();
        long total = hits + cfg.getMisses();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("similarityThreshold", cfg.getSimilarityThreshold());
        out.put("ttlSeconds", cfg.getTtlSeconds());
        out.put("hits", hits);
        out.put("misses", cfg.getMisses());
        out.put("hitRate", total == 0 ? 0.0 : (double) hits / total);
        out.put("tokensSaved", cfg.getTokensSaved());
        out.put("costSaved", cfg.getCostSaved());
        out.put("entries", entries.countByDeveloperId(developerId));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String developerId, int limit) {
        return entries.liveFor(developerId, Instant.now(), PageRequest.of(0, Math.max(1, Math.min(100, limit))))
                .stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("prompt", e.getPromptText().length() > 160
                            ? e.getPromptText().substring(0, 160) + "…" : e.getPromptText());
                    m.put("model", e.getModel());
                    m.put("provider", e.getProvider());
                    m.put("tokens", e.getTokens());
                    m.put("hitCount", e.getHitCount());
                    m.put("createdAt", e.getCreatedAt());
                    m.put("expiresAt", e.getExpiresAt());
                    return m;
                })
                .toList();
    }

    private SemanticCacheSettingEntity load(String developerId) {
        return settings.findById(developerId).orElseGet(() -> new SemanticCacheSettingEntity(developerId));
    }

    /**
     * Models match when they are the same, or when the caller did not ask for a
     * specific one. A request that named no model is happy with whatever answered
     * it last time.
     */
    private static boolean sameModel(String cached, String requested) {
        return requested == null || requested.isBlank() || requested.equalsIgnoreCase(cached);
    }

    /** Digest of the whitespace- and case-normalised prompt. */
    static String hash(String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String norm = prompt.trim().toLowerCase().replaceAll("\\s+", " ");
            return HexFormat.of().formatHex(md.digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
