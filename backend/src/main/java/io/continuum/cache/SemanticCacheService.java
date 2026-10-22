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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Separates, in a cache key, the conversation so far from the new question.
     * A near match needs the conversation to be the same, word for word, and is
     * scored on the question alone (see {@link #lookup}).
     */
    public static final String QUESTION = "\n[question]\n";

    /** A key's conversation so far ("" for a lone question) and its new question. */
    record Parts(String context, String question) {
        static Parts of(String key) {
            int i = key.lastIndexOf(QUESTION);
            return i < 0 ? new Parts("", key) : new Parts(key.substring(0, i), key.substring(i + QUESTION.length()));
        }
    }

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

            // Stage two: nearest neighbour above the threshold, among entries
            // that follow the same conversation. The whole key used to be
            // scored, and a long shared system prompt outweighed the question:
            // "how long does delivery take?" scored 0.97 against "what is your
            // return policy?" under the same 100-word store policy, and was
            // served its answer.
            List<SemanticCacheEntryEntity> candidates =
                    entries.liveFor(developerId, now, PageRequest.of(0, SCAN_LIMIT));
            Parts asked = Parts.of(prompt);
            Map<String, Integer> incoming = TextVectors.termFrequency(asked.question());
            SemanticCacheEntryEntity best = null;
            Parts bestParts = null;
            double bestScore = 0;
            for (SemanticCacheEntryEntity e : candidates) {
                if (!sameModel(e.getModel(), model)) {
                    continue;
                }
                Parts filed = Parts.of(e.getPromptText());
                if (!filed.context().equals(asked.context())) {
                    continue;
                }
                double score = TextVectors.cosine(incoming, TextVectors.termFrequency(filed.question()));
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                    bestParts = filed;
                }
            }
            // Word overlap cannot see the details that change an answer: one-
            // character numbers are not even tokens ("what is 2+2" and "what is
            // 3+3" scored 1.00), and "can I" and "can't I" differ only by an
            // apostrophe. A near match must agree on its numbers and negations.
            if (best != null && bestScore >= cfg.getSimilarityThreshold()
                    && sameSpecifics(asked.question(), bestParts.question())
                    && fewDifferences(asked.question(), bestParts.question())) {
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
                    String filed = e.getPromptText().replace(QUESTION, "\nuser: ");
                    m.put("prompt", clip(Parts.of(e.getPromptText()).question(), 160));
                    m.put("model", e.getModel());
                    m.put("provider", e.getProvider());
                    // What a stored entry is, for the console's detail view:
                    // the question as it was filed (already past the firewall)
                    // and the answer it will be served.
                    m.put("question", clip(filed, 2000));
                    m.put("answer", clip(e.getResponse(), 2000));
                    m.put("tokens", e.getTokens());
                    m.put("hitCount", e.getHitCount());
                    m.put("createdAt", e.getCreatedAt());
                    m.put("expiresAt", e.getExpiresAt());
                    return m;
                })
                .toList();
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Set<String> NEGATIONS = Set.of("not", "no", "never", "none", "nor", "without",
            "cannot", "can't", "cant", "don't", "dont", "doesn't", "doesnt", "didn't", "didnt", "isn't", "isnt",
            "aren't", "arent", "wasn't", "wasnt", "weren't", "werent", "won't", "wont", "shouldn't", "shouldnt",
            "wouldn't", "wouldnt", "couldn't", "couldnt", "mustn't", "neither");

    /** The numbers and negations in a prompt: what word overlap misses and an answer depends on. */
    static List<String> specifics(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        var m = NUMBER.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        for (String w : text.toLowerCase(java.util.Locale.ROOT).replace('’', '\'').split("[^a-z']+")) {
            if (NEGATIONS.contains(w)) {
                out.add("¬");
            }
        }
        return out;
    }

    static boolean sameSpecifics(String a, String b) {
        return specifics(a).equals(specifics(b));
    }

    /**
     * How many distinct words a near match may have that the other lacks, both
     * ways together. A rewording moves words around ("what is your return
     * policy?" / "your return policy, what is it?" differ by none); a different
     * question adds its own. Cosine alone could not tell when both questions sat
     * beside the same pasted log: "what is breaking checkout?" scored 1.00
     * against "what is breaking checkout, since when, and which orders are
     * affected?", the log outweighing the five words that make it another
     * question, and was served that answer.
     */
    static final int MAX_DIFFERING_TERMS = 3;

    static boolean fewDifferences(String a, String b) {
        java.util.Set<String> ta = TextVectors.termFrequency(a).keySet();
        java.util.Set<String> tb = TextVectors.termFrequency(b).keySet();
        long differing = ta.stream().filter(t -> !tb.contains(t)).count()
                + tb.stream().filter(t -> !ta.contains(t)).count();
        return differing <= MAX_DIFFERING_TERMS;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
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
