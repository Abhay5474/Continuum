package io.continuum.memory;

import io.continuum.aichaos.AiChaosEngine;
import io.continuum.persistence.entity.MemoryEntryEntity;
import io.continuum.persistence.repository.MemoryEntryRepository;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Extension 5 — hierarchical long-context memory for long-running agents.
 *
 * Memories live in PostgreSQL (outside the model context window). Retrieval
 * ranks by relevance/recency/salience and injects only the top-k within a token
 * budget — directly mitigating context overflow and lost-in-the-middle failures.
 * Compression summarizes cold memories; promotion/demotion moves entries between
 * tiers based on real access patterns.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryEntryRepository repo;
    private final RelevanceRanker ranker;
    private final ProviderRouter router;
    private final AiChaosEngine aiChaos;

    public MemoryService(MemoryEntryRepository repo, RelevanceRanker ranker,
                         ProviderRouter router, AiChaosEngine aiChaos) {
        this.repo = repo;
        this.ranker = ranker;
        this.router = router;
        this.aiChaos = aiChaos;
    }

    @Transactional
    public MemoryEntryEntity store(String scope, MemoryTier tier, String content, double salience) {
        return repo.save(new MemoryEntryEntity(scope, tier, content, salience));
    }

    /** Rank all of a scope's memories for the query and return the top-k (access-counted). */
    @Transactional
    public List<RetrievedMemory> retrieve(String scope, String query, int topK) {
        List<MemoryEntryEntity> all = repo.findByScope(scope);
        if (all.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<RelevanceRanker.Candidate> candidates = all.stream()
                .map(m -> new RelevanceRanker.Candidate(m.getId(), m.getContent(), m.getSalience(),
                        now.toEpochMilli() - m.getCreatedAt().toEpochMilli()))
                .toList();

        List<RelevanceRanker.Ranked> ranked = ranker.rank(query, candidates);
        List<RetrievedMemory> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            RelevanceRanker.Ranked r = ranked.get(i);
            MemoryEntryEntity entry = all.stream().filter(m -> m.getId().equals(r.id())).findFirst().orElseThrow();
            entry.markAccessed();
            repo.save(entry);
            String content = aiChaos.maybeCorruptMemory(entry.getContent(), scope); // Extension 3 hook
            out.add(new RetrievedMemory(entry.getId(), entry.getTier().name(), content,
                    r.score(), r.relevance()));
        }
        return out;
    }

    /** Build a bounded prompt context from the most relevant memories. */
    @Transactional
    public ContextResult buildContext(String scope, String query, int topK, int maxChars) {
        List<RetrievedMemory> top = retrieve(scope, query, topK);
        StringBuilder sb = new StringBuilder();
        List<Long> used = new ArrayList<>();
        for (RetrievedMemory m : top) {
            if (sb.length() + m.content().length() > maxChars) {
                break;
            }
            sb.append("- ").append(m.content()).append('\n');
            used.add(m.id());
        }
        return new ContextResult(sb.toString().trim(), used, top.size());
    }

    /**
     * Compress the oldest EPISODIC memories into a single LONG_TERM summary and
     * archive the originals. Uses the LLM when {@code useLlm} is set, otherwise a
     * deterministic offline extractive summary (free, reproducible).
     */
    @Transactional
    public MemoryEntryEntity compress(String scope, boolean useLlm) {
        List<MemoryEntryEntity> episodic = repo.findByScopeAndTier(scope, MemoryTier.EPISODIC);
        if (episodic.size() < 2) {
            return null;
        }
        String combined = episodic.stream().map(MemoryEntryEntity::getContent)
                .reduce((a, b) -> a + "\n" + b).orElse("");

        String summary = useLlm && !router.availableChain().isEmpty()
                ? llmSummary(combined) : extractiveSummary(episodic);

        MemoryEntryEntity summaryEntry = new MemoryEntryEntity(scope, MemoryTier.LONG_TERM,
                "[summary of " + episodic.size() + " memories] " + summary, 0.7);
        summaryEntry.setSummary(summary);
        MemoryEntryEntity saved = repo.save(summaryEntry);

        for (MemoryEntryEntity e : episodic) {
            e.setTier(MemoryTier.ARCHIVED);
            repo.save(e);
        }
        log.info("Compressed {} episodic memories into LONG_TERM summary for scope {}", episodic.size(), scope);
        return saved;
    }

    /** Housekeeping: promote frequently-used memories, demote stale ones. */
    @Transactional
    public int maintain(String scope) {
        int changed = 0;
        Instant cutoff = Instant.now().minusSeconds(3600);
        for (MemoryEntryEntity e : repo.findByScope(scope)) {
            if (e.getTier() == MemoryTier.EPISODIC && e.getAccessCount() >= 3) {
                e.setTier(MemoryTier.LONG_TERM);
                repo.save(e);
                changed++;
            } else if (e.getTier() == MemoryTier.WORKING && e.getCreatedAt().isBefore(cutoff)) {
                e.setTier(MemoryTier.EPISODIC);
                repo.save(e);
                changed++;
            }
        }
        return changed;
    }

    private String llmSummary(String combined) {
        try {
            var resp = router.complete(new LlmRequest(null, List.of(
                    Message.system("Summarize the following memories into 2-3 concise sentences."),
                    Message.user(combined)), 256, 0.2));
            return resp.content();
        } catch (Exception e) {
            return combined.length() > 400 ? combined.substring(0, 400) + "…" : combined;
        }
    }

    private String extractiveSummary(List<MemoryEntryEntity> entries) {
        // Take the first sentence of each, preferring higher-salience entries first.
        return entries.stream()
                .sorted((a, b) -> Double.compare(b.getSalience(), a.getSalience()))
                .map(e -> {
                    String c = e.getContent().strip();
                    int dot = c.indexOf('.');
                    return dot > 0 ? c.substring(0, dot + 1) : c;
                })
                .limit(5)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    @Transactional(readOnly = true)
    public List<MemoryEntryEntity> list(String scope) {
        return repo.findByScope(scope);
    }

    public record RetrievedMemory(Long id, String tier, String content, double score, double relevance) {
    }

    public record ContextResult(String context, List<Long> usedMemoryIds, int candidatesConsidered) {
    }
}
