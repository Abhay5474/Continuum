package io.continuum.mmu;

import io.continuum.godmode.memory.Summarizer;
import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.entity.MmuRequestMetricEntity;
import io.continuum.persistence.entity.MmuStubEntity;
import io.continuum.persistence.entity.MmuStubEventEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.persistence.repository.MmuRequestMetricRepository;
import io.continuum.persistence.repository.MmuStubEventRepository;
import io.continuum.persistence.repository.MmuStubRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import io.continuum.semantic.DecisionPolarity;
import io.continuum.semantic.TextVectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V7 — the Paging MMU: Continuum owns the Virtual Context Space; the LLM only
 * ever sees a bounded L1 slice.
 *
 * <pre>
 *   L1  active token window      the literal payload sent to the model (bounded)
 *   L2  semantic stubs           evicted segments replaced in-window by
 *                                [MEMORY_REF: id="…", summary="…"] references
 *   L3  per-stub event streams   immutable Postgres rows (CREATED base +
 *                                UPDATED deltas) — the disk
 * </pre>
 *
 * Pipeline per request (all inside {@link MmuSession}):
 * 1. EVICT      oldest non-system, non-recent messages are folded into
 *               deterministic segments; each becomes an L2 stub (dedup by
 *               content hash — resent histories hit the same stubs).
 * 2. PREFETCH   the newest user prompt is scanned against stub summaries;
 *               relevant pages are promoted from L3 back into L1 BEFORE the
 *               model runs, so only unexpected accesses fault.
 * 3. FAULT      if the model emits the provider-agnostic control token
 *               [PAGE_REQUEST: id="…"], generation is suspended, the page is
 *               lazily materialized from its own event stream, injected, and
 *               the call re-dispatched (bounded).
 * 4. COHERENCE  materialization folds ONLY the stub's stream — base ⊕
 *               subsequent mutations — so the model never sees stale state.
 * 5. DIRTY      a response that reverses a paged decision marks the stub
 *               DIRTY in memory; the UPDATED event is flushed write-behind at
 *               the next natural checkpoint (the next MMU request), keeping
 *               heavy writes off the hot path. No threads anywhere.
 *
 * Compatibility: {@link #open} returns {@code null} unless the developer
 * flipped {@code v7_mmu_enabled} (default FALSE) — null ⇒ the caller's legacy
 * path is untouched, the full prompt array goes to the model as always.
 */
@Service
public class ContextMMU {

    private static final Logger log = LoggerFactory.getLogger(ContextMMU.class);
    static final Pattern PAGE_REQUEST = Pattern.compile("\\[PAGE_REQUEST:\\s*id=\"([^\"]+)\"\\s*]");
    private static final Pattern MEMORY_REF = Pattern.compile("\\[MEMORY_REF:\\s*id=\"([^\"]+)\"");
    private static final int RECENT_KEEP = 4;      // newest messages never evicted
    private static final int SEGMENT_TOKENS = 800; // eviction granularity
    private static final int MAX_FAULT_ROUNDS = 2;
    private static final double PREFETCH_RELEVANCE = 0.30;

    private final DeveloperAuthRepository devAuth;
    private final MmuStubRepository stubs;
    private final MmuStubEventRepository stubEvents;
    private final MmuRequestMetricRepository metrics;
    private final int l1BudgetTokens;

    /** Write-behind dirty set: stubId → superseding note. Flushed at the next checkpoint. */
    private final Map<String, String> pendingDirty = new ConcurrentHashMap<>();

    public ContextMMU(DeveloperAuthRepository devAuth, MmuStubRepository stubs,
                      MmuStubEventRepository stubEvents, MmuRequestMetricRepository metrics,
                      @Value("${continuum.mmu.l1-budget-tokens:6000}") int l1BudgetTokens) {
        this.devAuth = devAuth;
        this.stubs = stubs;
        this.stubEvents = stubEvents;
        this.metrics = metrics;
        this.l1BudgetTokens = l1BudgetTokens;
    }

    public boolean enabledFor(String developerId) {
        return developerId != null && devAuth.findById(developerId)
                .map(DeveloperAuthEntity::isV7MmuEnabled).orElse(false);
    }

    @Transactional
    public boolean setEnabled(String developerId, boolean enabled) {
        DeveloperAuthEntity auth = devAuth.findById(developerId)
                .orElseThrow(() -> new IllegalStateException("No portal account for developer"));
        auth.setV7MmuEnabled(enabled);
        devAuth.save(auth);
        log.info("V7 Context MMU {} for developer {}", enabled ? "ENABLED" : "DISABLED", developerId);
        return enabled;
    }

    /**
     * The single entry point. Returns {@code null} when the developer has not
     * opted in — the caller then runs its exact pre-V7 path.
     */
    public MmuSession open(String developerId, LlmRequest request) {
        if (!enabledFor(developerId)) {
            return null;
        }
        try {
            flushDirtyCheckpoint(developerId); // write-behind: previous request's dirt
            return new MmuSession(developerId, request);
        } catch (Exception e) {
            log.warn("MMU open failed for {}, running legacy path: {}", developerId, e.getMessage());
            return null;
        }
    }

    /** One request's journey through the MMU. */
    public final class MmuSession {

        private final String developerId;
        private final int tokensWithoutMmu;
        private final LlmRequest virtualized;
        private final Set<String> stubsInWindow = new HashSet<>();
        private int stubsCreated;
        private int prefetches;
        private int pageFaults;
        private long faultLatencyMs;
        private long materializationMs;
        private int dirtyFlushed;

        private MmuSession(String developerId, LlmRequest original) {
            this.developerId = developerId;
            this.tokensWithoutMmu = tokensOf(original.messages());
            this.dirtyFlushed = lastFlushCount.getOrDefault(developerId, 0);
            lastFlushCount.remove(developerId);
            List<Message> evicted = evict(original.messages());
            List<Message> prefetched = prefetch(evicted, original.messages());
            this.virtualized = new LlmRequest(original.model(), prefetched,
                    original.maxTokens(), original.temperature());
        }

        /** The bounded L1 request actually sent to the provider. */
        public LlmRequest request() {
            return virtualized;
        }

        /**
         * Page-fault interception: while the model requests a paged segment via
         * the control token, suspend, materialize from L3, inject, re-dispatch.
         */
        public LlmResponse interceptFaults(LlmResponse response,
                                           Function<LlmRequest, LlmResponse> redispatch) {
            LlmResponse current = response;
            List<Message> working = new ArrayList<>(virtualized.messages());
            for (int round = 0; round < MAX_FAULT_ROUNDS; round++) {
                Matcher m = PAGE_REQUEST.matcher(current.content() == null ? "" : current.content());
                if (!m.find()) {
                    break;
                }
                String stubId = m.group(1);
                long t0 = System.nanoTime();
                String page = materialize(stubId);
                if (page == null) {
                    break; // unknown page: let the response stand
                }
                pageFaults++;
                working.add(new Message(Role.ASSISTANT, current.content(), List.of()));
                working.add(Message.system("[PAGE_IN id=\"" + stubId + "\"] Full paged context "
                        + "restored below. Continue the original answer using it; do not emit "
                        + "further PAGE_REQUEST tokens for this id.\n" + page));
                LlmRequest faultRequest = new LlmRequest(virtualized.model(), List.copyOf(working),
                        virtualized.maxTokens(), virtualized.temperature());
                current = redispatch.apply(faultRequest);
                faultLatencyMs += (System.nanoTime() - t0) / 1_000_000;
            }
            return current;
        }

        /** Final checkpoint: dirty detection + telemetry. Never throws. */
        public void finish(LlmResponse response) {
            try {
                markDirtyReversals(response);
                metrics.save(new MmuRequestMetricEntity(developerId, tokensWithoutMmu,
                        tokensOf(virtualized.messages()), stubsInWindow.size(), stubsCreated,
                        prefetches, pageFaults, faultLatencyMs, materializationMs, dirtyFlushed));
            } catch (Exception e) {
                log.debug("MMU telemetry skipped: {}", e.getMessage());
            }
        }

        // ---- L1 → L2 eviction ----

        private List<Message> evict(List<Message> messages) {
            if (tokensOf(messages) <= l1BudgetTokens) {
                return messages;
            }
            List<Message> out = new ArrayList<>();
            List<Message> evictable = new ArrayList<>();
            int keepFrom = Math.max(0, messages.size() - RECENT_KEEP);
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                if (msg.role() == Role.SYSTEM || i >= keepFrom) {
                    out.add(msg); // pinned pages: system prompt + the recent window
                } else {
                    evictable.add(msg);
                }
            }
            // Fold evictable history into deterministic segments, oldest first.
            // Granularity adapts to the budget so a promoted page always fits
            // back into L1 (an OS swaps pages; it never refuses to page in).
            int segmentLimit = Math.max(200, Math.min(SEGMENT_TOKENS, l1BudgetTokens / 4));
            List<Message> paged = new ArrayList<>();
            List<Message> segment = new ArrayList<>();
            int segmentTokens = 0;
            for (Message msg : evictable) {
                segment.add(msg);
                segmentTokens += tokensOf(List.of(msg));
                if (segmentTokens >= segmentLimit) {
                    paged.add(pageOut(segment));
                    segment = new ArrayList<>();
                    segmentTokens = 0;
                }
            }
            if (!segment.isEmpty()) {
                paged.add(pageOut(segment));
            }
            // Rebuild: system prompt(s) + stubs + paging instruction + recent window.
            List<Message> rebuilt = new ArrayList<>();
            int recentStart = out.size() - Math.min(RECENT_KEEP, out.size());
            for (int i = 0; i < out.size(); i++) {
                if (i == recentStart && !paged.isEmpty()) {
                    rebuilt.addAll(paged);
                    rebuilt.add(Message.system("Earlier context was paged out as [MEMORY_REF] "
                            + "stubs above. If you need a stub's full detail, reply with exactly "
                            + "[PAGE_REQUEST: id=\"<id>\"] and nothing else."));
                }
                rebuilt.add(out.get(i));
            }
            return rebuilt;
        }

        private Message pageOut(List<Message> segment) {
            StringBuilder raw = new StringBuilder();
            for (Message m : segment) {
                raw.append(m.role()).append(": ").append(m.content()).append('\n');
            }
            String content = raw.toString();
            String stubId = "seg_" + sha256(developerId + "|" + content).substring(0, 16);
            MmuStubEntity stub = stubs.findById(stubId).orElse(null);
            if (stub == null) {
                String summary = new Summarizer()
                        .summarize(segment.stream().map(Message::content).toList()).text();
                if (summary.length() > 220) {
                    summary = summary.substring(0, 220) + "…";
                }
                stub = stubs.save(new MmuStubEntity(stubId, developerId, summary,
                        Summarizer.estimateTokens(content), Summarizer.estimateTokens(summary)));
                stubEvents.save(new MmuStubEventEntity(stubId, developerId, 1, "CREATED", content));
                stubsCreated++;
            }
            stubsInWindow.add(stubId);
            return Message.system("[MEMORY_REF: id=\"" + stubId + "\", summary=\""
                    + stub.getSummary().replace('"', '\'') + "\"]");
        }

        // ---- predictive prefetching ----

        private List<Message> prefetch(List<Message> messages, List<Message> original) {
            if (stubsInWindow.isEmpty()) {
                return messages;
            }
            String newest = null;
            for (int i = original.size() - 1; i >= 0; i--) {
                if (original.get(i).role() == Role.USER) {
                    newest = original.get(i).content();
                    break;
                }
            }
            if (newest == null) {
                return messages;
            }
            List<Message> out = new ArrayList<>(messages.size());
            // Promotion allowance: at least 60% of the budget is always available
            // for paging relevant history back in (swap semantics, not refusal).
            int budgetLeft = Math.max(l1BudgetTokens - tokensOf(messages),
                    (int) (l1BudgetTokens * 0.6));
            for (Message msg : messages) {
                Matcher ref = msg.role() == Role.SYSTEM && msg.content() != null
                        ? MEMORY_REF.matcher(msg.content()) : null;
                if (ref != null && ref.find()) {
                    String stubId = ref.group(1);
                    MmuStubEntity stub = stubs.findById(stubId).orElse(null);
                    boolean explicit = newest.contains(stubId);
                    double relevance = stub == null ? 0
                            : TextVectors.cosine(newest, stub.getSummary());
                    if (stub != null && (explicit || relevance >= PREFETCH_RELEVANCE)) {
                        String page = materialize(stubId);
                        int cost = Summarizer.estimateTokens(page);
                        if (page != null && cost <= budgetLeft) {
                            out.add(Message.system("[PAGE_IN id=\"" + stubId
                                    + "\" reason=\"prefetch\"]\n" + page));
                            budgetLeft -= cost;
                            prefetches++;
                            continue;
                        }
                    }
                }
                out.add(msg);
            }
            return out;
        }

        // ---- context coherence: lazy materialization ----

        /**
         * Fold ONLY this stub's event stream: the CREATED base plus every
         * subsequent UPDATED delta. O(events-for-this-stub) — never a replay
         * of the whole conversation or workflow.
         */
        private String materialize(String stubId) {
            long t0 = System.nanoTime();
            List<MmuStubEventEntity> stream = stubEvents.findByStubIdOrderBySeqAsc(stubId);
            if (stream.isEmpty()) {
                return null;
            }
            StringBuilder view = new StringBuilder(stream.get(0).getContent());
            for (int i = 1; i < stream.size(); i++) {
                view.append("\n[STATE UPDATE ").append(stream.get(i).getSeq()).append("] ")
                        .append(stream.get(i).getContent());
            }
            materializationMs += (System.nanoTime() - t0) / 1_000_000;
            return view.toString();
        }

        // ---- dirty pages (write-behind) ----

        private void markDirtyReversals(LlmResponse response) {
            String content = response == null ? null : response.content();
            if (content == null || content.isBlank() || stubsInWindow.isEmpty()) {
                return;
            }
            for (String stubId : stubsInWindow) {
                MmuStubEntity stub = stubs.findById(stubId).orElse(null);
                if (stub == null) {
                    continue;
                }
                DecisionPolarity.IntentResult intent =
                        DecisionPolarity.consistency(stub.getSummary(), content);
                if (intent.reversed()) {
                    String note = "Decision superseded by a later exchange: "
                            + lastSentence(content);
                    pendingDirty.put(stubId, note);
                    log.info("MMU: stub {} marked DIRTY (write-behind): {}",
                            stubId, intent.explanation());
                }
            }
        }
    }

    /** Tracks flushes performed by the checkpoint so the NEXT session reports them. */
    private final Map<String, Integer> lastFlushCount = new ConcurrentHashMap<>();

    /**
     * The lazy-flush checkpoint: pending dirty marks are appended as UPDATED
     * events (bumping the stub version and refreshing its summary) at the start
     * of the next MMU-processed request — never on the hot path that got dirty.
     */
    @Transactional
    public void flushDirtyCheckpoint(String developerId) {
        int flushed = 0;
        for (var it = pendingDirty.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            MmuStubEntity stub = stubs.findById(entry.getKey()).orElse(null);
            if (stub == null || !stub.getDeveloperId().equals(developerId)) {
                continue;
            }
            int nextSeq = stubEvents.countByStubId(stub.getStubId()) + 1;
            stubEvents.save(new MmuStubEventEntity(stub.getStubId(), developerId,
                    nextSeq, "UPDATED", entry.getValue()));
            stub.setSummary(stub.getSummary() + " [updated: " + entry.getValue() + "]");
            stub.setDirty(false);
            stub.bumpVersion();
            stubs.save(stub);
            it.remove();
            flushed++;
        }
        if (flushed > 0) {
            lastFlushCount.put(developerId, flushed);
            log.info("MMU: flushed {} dirty page(s) for {} at checkpoint", flushed, developerId);
        }
    }

    // ---- profiler reads ----

    @Transactional(readOnly = true)
    public Map<String, Object> profile(String developerId) {
        List<MmuRequestMetricEntity> rows = developerId == null || developerId.isBlank()
                ? metrics.findTop200ByOrderByCreatedAtDesc()
                : metrics.findTop200ByDeveloperIdOrderByCreatedAtDesc(developerId);
        long without = rows.stream().mapToLong(MmuRequestMetricEntity::getTokensWithoutMmu).sum();
        long sent = rows.stream().mapToLong(MmuRequestMetricEntity::getTokensSent).sum();
        List<Long> faultLatencies = rows.stream()
                .filter(r -> r.getPageFaults() > 0)
                .map(MmuRequestMetricEntity::getFaultLatencyMs).sorted().toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", rows.size());
        out.put("tokensWithoutMmu", without);
        out.put("tokensSent", sent);
        out.put("tokenReduction", without == 0 ? 0 : 1.0 - (double) sent / without);
        out.put("estimatedCostSaved", without == 0 ? 0 : 1.0 - (double) sent / without);
        out.put("compressionRatio", sent == 0 ? 1 : (double) without / sent);
        out.put("pageFaults", rows.stream().mapToInt(MmuRequestMetricEntity::getPageFaults).sum());
        out.put("prefetches", rows.stream().mapToInt(MmuRequestMetricEntity::getPrefetches).sum());
        out.put("faultLatencyP50Ms", percentile(faultLatencies, 0.50));
        out.put("faultLatencyP95Ms", percentile(faultLatencies, 0.95));
        out.put("avgMaterializationMs", rows.isEmpty() ? 0 : rows.stream()
                .mapToLong(MmuRequestMetricEntity::getMaterializationMs).average().orElse(0));
        out.put("dirtyFlushes", rows.stream().mapToInt(MmuRequestMetricEntity::getDirtyFlushes).sum());
        out.put("recent", rows.subList(0, Math.min(30, rows.size())));
        return out;
    }

    public List<MmuStubEntity> recentStubs(String developerId) {
        return stubs.findTop100ByDeveloperIdOrderByUpdatedAtDesc(developerId);
    }

    // ---- helpers ----

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static int tokensOf(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += Summarizer.estimateTokens(m.content());
        }
        return total;
    }

    private static String lastSentence(String text) {
        String[] parts = text.trim().split("(?<=[.!?])\\s+");
        String last = parts.length == 0 ? text : parts[parts.length - 1];
        return last.length() > 200 ? last.substring(0, 200) + "…" : last;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
