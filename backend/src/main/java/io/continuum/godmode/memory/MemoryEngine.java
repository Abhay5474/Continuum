package io.continuum.godmode.memory;

import io.continuum.autopilot.engine.MemActEngine;
import io.continuum.common.Json;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 4-tier God Mode memory engine (the V2 Extension-5 upgrade):
 *
 * <pre>
 *   WORKING ──summarize──▶ EPISODIC ──promote──▶ SEMANTIC GRAPH ──decay──▶ ARCHIVE
 * </pre>
 *
 * Consolidation is driven by the {@link MemActEngine} (Memory-as-Action: the
 * same Thompson-sampling machinery as V4, applied to memory operations) and is
 * strictly bounded: every row carries a TTL, every tier has a per-tenant quota,
 * and every query is developer-scoped. Summarization is deterministic and
 * local ({@link Summarizer}) — no tokens spent, no replay non-determinism.
 */
@Service
public class MemoryEngine {

    private static final Logger log = LoggerFactory.getLogger(MemoryEngine.class);
    private static final double DUPLICATE_SIMILARITY = 0.92;
    private static final double EDGE_SIMILARITY = 0.55;
    private static final int PROMOTE_MIN_SUMMARY_TOKENS = 20;

    private final MemoryWorkingRepository working;
    private final MemoryEpisodicRepository episodic;
    private final ExperienceNodeRepository nodes;
    private final ExperienceEdgeRepository edges;
    private final MemoryArchiveRepository archives;
    private final GodModeActionRepository actions;
    private final MemActEngine memAct;
    private final Summarizer summarizer = new Summarizer();
    private final TextEmbedder embedder = new TextEmbedder();
    private final Json json;

    public MemoryEngine(MemoryWorkingRepository working, MemoryEpisodicRepository episodic,
                        ExperienceNodeRepository nodes, ExperienceEdgeRepository edges,
                        MemoryArchiveRepository archives, GodModeActionRepository actions,
                        MemActEngine memAct, Json json) {
        this.working = working;
        this.episodic = episodic;
        this.nodes = nodes;
        this.edges = edges;
        this.archives = archives;
        this.actions = actions;
        this.memAct = memAct;
        this.json = json;
    }

    // ---- ingestion (called from the gateway observe hook; enabled tenants only) ----

    @Transactional
    public void ingest(GodModeConfigEntity config, String sessionId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String dev = config.getDeveloperId();
        if (working.countByDeveloperId(dev) >= config.getMaxWorkingItems()) {
            // Quota pressure: consolidate before accepting more (bounded growth).
            consolidateWorking(config, true);
        }
        Instant expires = Instant.now().plus(Duration.ofMinutes(config.getWorkingTtlMinutes()));
        working.save(new MemoryWorkingEntity(dev, sessionId == null ? "default" : sessionId,
                role == null ? "user" : role, content, Summarizer.estimateTokens(content), expires));
        audit(dev, "STORE", "WORKING", "ingested " + Summarizer.estimateTokens(content) + " tokens", null);
    }

    // ---- the consolidation pipeline (one tick per enabled developer) ----

    @Transactional
    public Map<String, Object> consolidate(GodModeConfigEntity config) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summarized", consolidateWorking(config, false));
        report.put("promoted", promoteEpisodic(config));
        report.put("pruned", pruneSemanticDuplicates(config));
        report.put("archived", archiveDecayed(config));
        report.put("expired", expireAll());
        return report;
    }

    /** Working → Episodic: MemAct decides when; summarization is the reward signal. */
    private int consolidateWorking(GodModeConfigEntity config, boolean forced) {
        String dev = config.getDeveloperId();
        long tokens = working.totalTokens(dev);
        double fill = config.getContextBudgetTokens() == 0 ? 0
                : (double) tokens / config.getContextBudgetTokens();
        if (!forced && !memAct.shouldSummarize(fill)) {
            audit(dev, "DEFER", "WORKING", String.format("context fill %.0f%%", fill * 100), null);
            return 0;
        }
        List<MemoryWorkingEntity> items = working.findByDeveloperIdOrderByCreatedAtAsc(dev);
        if (items.size() < 3) {
            return 0; // too little signal to be worth compressing
        }
        List<String> texts = items.stream().map(MemoryWorkingEntity::getContent).toList();
        Summarizer.Summary s = summarizer.summarize(texts);
        double reward = 1.0 - s.compressionRatio(); // more compression = more value
        Instant expires = Instant.now().plus(Duration.ofDays(config.getEpisodicTtlDays()));
        episodic.save(new MemoryEpisodicEntity(dev, items.get(0).getSessionId(), s.text(),
                items.size(), s.inputTokens(), s.summaryTokens(),
                json.write(embedder.embed(s.text())), expires));
        working.deleteAll(items);
        memAct.observe(MemActEngine.MemoryAction.SUMMARIZE_NOW, reward);
        audit(dev, "SUMMARIZE", "EPISODIC", items.size() + " items, " + s.inputTokens()
                + " → " + s.summaryTokens() + " tokens", reward);
        return items.size();
    }

    /** Episodic → Semantic graph: dense summaries become experience nodes with similarity edges. */
    private int promoteEpisodic(GodModeConfigEntity config) {
        if (!memAct.shouldRun(MemActEngine.MemoryAction.PROMOTE_EXPERIENCE)) {
            return 0;
        }
        String dev = config.getDeveloperId();
        if (nodes.countByDeveloperId(dev) >= config.getMaxSemanticNodes()) {
            return 0;
        }
        int promoted = 0;
        List<ExperienceNodeEntity> existing = nodes.findByDeveloperIdOrderByUtilityScoreDesc(dev);
        for (MemoryEpisodicEntity e : episodic.findByDeveloperIdOrderByCreatedAtDesc(dev)) {
            if (e.getSummaryTokens() < PROMOTE_MIN_SUMMARY_TOKENS) {
                continue;
            }
            double[] emb = e.getEmbeddingJson() != null
                    ? json.read(e.getEmbeddingJson(), double[].class) : embedder.embed(e.getSummaryText());
            // Near-duplicate of an existing experience? reinforce instead of duplicating.
            ExperienceNodeEntity dup = nearest(existing, emb, DUPLICATE_SIMILARITY);
            if (dup != null) {
                dup.setUtilityScore(Math.min(1.0, dup.getUtilityScore() + 0.05));
                dup.markUsed();
                nodes.save(dup);
                episodic.delete(e);
                continue;
            }
            Instant expires = Instant.now().plus(Duration.ofDays(config.getSemanticTtlDays()));
            ExperienceNodeEntity node = nodes.save(new ExperienceNodeEntity(
                    dev, "EXPERIENCE", e.getSummaryText(), json.write(emb), 0.5, expires));
            // Connect to semantically-related experiences (the experience graph).
            for (ExperienceNodeEntity other : existing) {
                double sim = TextEmbedder.cosine(emb, embeddingOf(other));
                if (sim >= EDGE_SIMILARITY) {
                    edges.save(new ExperienceEdgeEntity(dev, node.getId(), other.getId(), "SIMILAR", sim));
                }
            }
            existing.add(node);
            episodic.delete(e);
            promoted++;
        }
        if (promoted > 0) {
            memAct.observe(MemActEngine.MemoryAction.PROMOTE_EXPERIENCE, 0.8);
            audit(dev, "PROMOTE", "SEMANTIC", promoted + " experience node(s) created", 0.8);
        }
        return promoted;
    }

    /** Semantic dedup: drop redundant nodes (interference-based forgetting). */
    private int pruneSemanticDuplicates(GodModeConfigEntity config) {
        if (!memAct.shouldRun(MemActEngine.MemoryAction.PRUNE_DUPLICATES)) {
            return 0;
        }
        String dev = config.getDeveloperId();
        List<ExperienceNodeEntity> all = nodes.findByDeveloperIdOrderByUtilityScoreDesc(dev);
        int pruned = 0;
        for (int i = 0; i < all.size(); i++) {
            for (int j = all.size() - 1; j > i; j--) {
                double sim = TextEmbedder.cosine(embeddingOf(all.get(i)), embeddingOf(all.get(j)));
                if (sim >= DUPLICATE_SIMILARITY) {
                    ExperienceNodeEntity loser = all.remove(j); // lower-utility duplicate
                    edges.deleteByFromNodeIdOrToNodeId(loser.getId(), loser.getId());
                    nodes.delete(loser);
                    pruned++;
                }
            }
        }
        memAct.observe(MemActEngine.MemoryAction.PRUNE_DUPLICATES, pruned > 0 ? 0.9 : 0.3);
        if (pruned > 0) {
            audit(dev, "PRUNE", "SEMANTIC", pruned + " near-duplicate node(s) removed", 0.9);
        }
        return pruned;
    }

    /** Semantic → Archive: expired / low-utility experiences compress into cold storage. */
    private int archiveDecayed(GodModeConfigEntity config) {
        if (!memAct.shouldRun(MemActEngine.MemoryAction.ARCHIVE_COLD)) {
            return 0;
        }
        String dev = config.getDeveloperId();
        List<ExperienceNodeEntity> expired = nodes.expiredFor(dev, Instant.now());
        if (expired.isEmpty()) {
            return 0;
        }
        Summarizer.Summary s = summarizer.summarize(
                expired.stream().map(ExperienceNodeEntity::getText).toList());
        archives.save(new MemoryArchiveEntity(dev,
                "archived " + expired.size() + " experiences", s.text(), expired.size()));
        for (ExperienceNodeEntity n : expired) {
            edges.deleteByFromNodeIdOrToNodeId(n.getId(), n.getId());
        }
        nodes.deleteAll(expired);
        memAct.observe(MemActEngine.MemoryAction.ARCHIVE_COLD, 0.8);
        audit(dev, "ARCHIVE", "ARCHIVE", expired.size() + " node(s) compressed to cold storage", 0.8);
        return expired.size();
    }

    /** Global TTL sweep across the hot tiers (bounded growth, all tenants). */
    private int expireAll() {
        return working.deleteExpired(Instant.now()) + episodic.deleteExpired(Instant.now());
    }

    // ---- retrieval & introspection (all developer-scoped) ----

    @Transactional(readOnly = true)
    public Map<String, Object> tierSnapshot(GodModeConfigEntity config) {
        String dev = config.getDeveloperId();
        long workingTokens = working.totalTokens(dev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("working", Map.of("items", working.countByDeveloperId(dev), "tokens", workingTokens));
        out.put("episodic", Map.of("items", episodic.countByDeveloperId(dev)));
        out.put("semantic", Map.of("nodes", nodes.countByDeveloperId(dev)));
        out.put("archive", Map.of("spans", archives.countByDeveloperId(dev)));
        out.put("contextBudgetTokens", config.getContextBudgetTokens());
        out.put("contextFillFraction", config.getContextBudgetTokens() == 0 ? 0
                : Math.min(1.0, (double) workingTokens / config.getContextBudgetTokens()));
        return out;
    }

    /** Relevance-ranked retrieval across episodic + semantic tiers for a query. */
    @Transactional
    public List<Map<String, Object>> retrieve(GodModeConfigEntity config, String query, int limit) {
        String dev = config.getDeveloperId();
        double[] q = embedder.embed(query);
        record Hit(String tier, String text, double score, ExperienceNodeEntity node) {
        }
        List<Hit> hits = new ArrayList<>();
        for (MemoryEpisodicEntity e : episodic.findByDeveloperIdOrderByCreatedAtDesc(dev)) {
            double[] emb = e.getEmbeddingJson() != null
                    ? json.read(e.getEmbeddingJson(), double[].class) : embedder.embed(e.getSummaryText());
            hits.add(new Hit("EPISODIC", e.getSummaryText(), TextEmbedder.cosine(q, emb), null));
        }
        for (ExperienceNodeEntity n : nodes.findByDeveloperIdOrderByUtilityScoreDesc(dev)) {
            double sim = TextEmbedder.cosine(q, embeddingOf(n));
            hits.add(new Hit("SEMANTIC", n.getText(), sim * (0.7 + 0.3 * n.getUtilityScore()), n));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit h : hits.subList(0, Math.min(limit, hits.size()))) {
            if (h.node() != null) {
                h.node().markUsed();
                h.node().setUtilityScore(Math.min(1.0, h.node().getUtilityScore() + 0.02));
                nodes.save(h.node());
            }
            out.add(Map.of("tier", h.tier(), "text", h.text(), "score", h.score()));
        }
        if (!out.isEmpty()) {
            audit(dev, "RETRIEVE", null, out.size() + " memories retrieved", null);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> experienceGraph(String developerId) {
        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (ExperienceNodeEntity n : nodes.findByDeveloperIdOrderByUtilityScoreDesc(developerId)) {
            nodeList.add(Map.of("id", n.getId(), "kind", n.getKind(),
                    "text", n.getText().length() > 140 ? n.getText().substring(0, 140) + "…" : n.getText(),
                    "utility", n.getUtilityScore(), "uses", n.getUses()));
        }
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (ExperienceEdgeEntity e : edges.findByDeveloperId(developerId)) {
            edgeList.add(Map.of("from", e.getFromNodeId(), "to", e.getToNodeId(),
                    "relation", e.getRelation(), "weight", e.getWeight()));
        }
        return Map.of("nodes", nodeList, "edges", edgeList);
    }

    /** Developer-requested wipe of their own memory (privacy control). */
    @Transactional
    public void wipe(String developerId) {
        working.deleteByDeveloperId(developerId);
        episodic.deleteByDeveloperId(developerId);
        edges.deleteByDeveloperId(developerId);
        nodes.deleteByDeveloperId(developerId);
        archives.deleteByDeveloperId(developerId);
        audit(developerId, "PRUNE", null, "developer wiped all memory tiers", null);
    }

    public List<GodModeActionEntity> recentActions(String developerId) {
        return actions.findTop100ByDeveloperIdOrderByCreatedAtDesc(developerId);
    }

    public Map<String, Object> memActState() {
        return memAct.stateSnapshot();
    }

    private ExperienceNodeEntity nearest(List<ExperienceNodeEntity> candidates, double[] emb, double minSim) {
        ExperienceNodeEntity best = null;
        double bestSim = minSim;
        for (ExperienceNodeEntity n : candidates) {
            double sim = TextEmbedder.cosine(emb, embeddingOf(n));
            if (sim >= bestSim) {
                bestSim = sim;
                best = n;
            }
        }
        return best;
    }

    private double[] embeddingOf(ExperienceNodeEntity n) {
        return n.getEmbeddingJson() != null
                ? json.read(n.getEmbeddingJson(), double[].class) : embedder.embed(n.getText());
    }

    private void audit(String dev, String action, String tier, String detail, Double reward) {
        try {
            actions.save(new GodModeActionEntity(dev, action, tier, detail, reward));
        } catch (Exception e) {
            log.debug("memact audit skipped: {}", e.getMessage());
        }
    }
}
