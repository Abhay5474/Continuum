package io.continuum.compression;

import io.continuum.persistence.entity.CompressionMetricEntity;
import io.continuum.persistence.entity.CompressionPolicySettingEntity;
import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.repository.CompressionMetricRepository;
import io.continuum.persistence.repository.CompressionPolicySettingRepository;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V8 gateway integration for LLMLingua-inspired {@link PromptCompressor}.
 *
 * Gated by {@code developer_auth.v8_compression_enabled} (OFF by default): when
 * off, {@link #maybeCompress} returns the request unchanged — the exact legacy
 * path. When on, only the large system/user context messages are compressed
 * (the most recent user turn is preserved verbatim so instructions aren't
 * mangled), and the token savings are recorded for the before/after metric.
 */
@Service
public class PromptCompressionService {

    private static final Logger log = LoggerFactory.getLogger(PromptCompressionService.class);

    private final DeveloperAuthRepository devAuth;
    private final CompressionMetricRepository metrics;
    private final CompressionPolicySettingRepository policyRepo;
    private final PromptCompressor compressor = new PromptCompressor();
    private final double targetRatio;
    private final int minTokens;

    /**
     * Live per-region tallies, for the console.
     *
     * <p>Held in memory rather than persisted: the aggregate token savings are
     * already durable in {@code compression_metrics}, and what this adds is the
     * per-region breakdown that shows the budget controller doing its job. It is
     * reset by a restart, which the page states.
     */
    private final Map<String, RegionTally> regionTallies = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> skips = new ConcurrentHashMap<>();

    private static final class RegionTally {
        final AtomicLong messages = new AtomicLong();
        final AtomicLong tokensIn = new AtomicLong();
        final AtomicLong tokensOut = new AtomicLong();
    }

    public PromptCompressionService(DeveloperAuthRepository devAuth, CompressionMetricRepository metrics,
                                    CompressionPolicySettingRepository policyRepo,
                                    @Value("${continuum.compression.target-ratio:0.55}") double targetRatio,
                                    @Value("${continuum.compression.min-tokens:60}") int minTokens) {
        this.devAuth = devAuth;
        this.metrics = metrics;
        this.policyRepo = policyRepo;
        this.targetRatio = targetRatio;
        this.minTokens = minTokens;
    }

    /** Whether the adaptive budget controller is on for this tenant. */
    @Transactional(readOnly = true)
    public boolean policyEnabledFor(String developerId) {
        return developerId != null && policyRepo.findById(developerId)
                .map(CompressionPolicySettingEntity::isEnabled).orElse(false);
    }

    public boolean enabledFor(String developerId) {
        return developerId != null && devAuth.findById(developerId)
                .map(DeveloperAuthEntity::isV8CompressionEnabled).orElse(false);
    }

    @Transactional
    public boolean setEnabled(String developerId, boolean enabled) {
        DeveloperAuthEntity auth = devAuth.findById(developerId)
                .orElseThrow(() -> new IllegalStateException("No portal account for developer"));
        auth.setV8CompressionEnabled(enabled);
        devAuth.save(auth);
        log.info("V8 Prompt Compression {} for developer {}", enabled ? "ENABLED" : "DISABLED", developerId);
        return enabled;
    }

    /**
     * Compress the request's context messages if the developer opted in. Never
     * throws into the caller; on any error the original request is returned.
     */
    public LlmRequest maybeCompress(String developerId, LlmRequest request) {
        if (!enabledFor(developerId) || request == null || request.messages() == null) {
            return request;
        }
        try {
            List<Message> messages = request.messages();
            int lastUserIdx = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role() == Role.USER) {
                    lastUserIdx = i;
                    break;
                }
            }

            boolean adaptive = policyEnabledFor(developerId);

            // The budget controller's first decision is whether to compress at
            // all. A short prompt has little to remove, and removing it costs
            // fidelity for nothing.
            if (adaptive) {
                int promptTokens = 0;
                for (Message m : messages) {
                    promptTokens += PromptCompressor.estimateTokens(m.content());
                }
                // Price is deliberately not consulted here: the gateway selects a
                // model *after* this point, so at this moment there is genuinely
                // nothing to price. Passing null says that rather than inventing
                // a figure.
                CompressionPolicy.Gate gate = CompressionPolicy.gate(promptTokens, null);
                if (!gate.compress()) {
                    countSkip(developerId, gate.reason());
                    return request;
                }
            }

            List<Message> out = new ArrayList<>(messages.size());
            int origTotal = 0;
            int compTotal = 0;
            int protectedTotal = 0;
            boolean any = false;
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                // Preserve the most recent user instruction verbatim.
                if (i == lastUserIdx || msg.content() == null) {
                    if (adaptive && msg.content() != null) {
                        tally(developerId, CompressionPolicy.Region.QUESTION,
                                PromptCompressor.estimateTokens(msg.content()),
                                PromptCompressor.estimateTokens(msg.content()));
                    }
                    out.add(msg);
                    continue;
                }
                // One ratio for everything, or LLMLingua's per-region budget.
                CompressionPolicy.Region region = adaptive
                        ? CompressionPolicy.classify(msg.role(), msg.content(), false)
                        : null;
                double ratio = region == null ? targetRatio : region.keepRatio();

                PromptCompressor.Result r = compressor.compress(msg.content(), ratio, minTokens);
                if (adaptive) {
                    tally(developerId, region, r.originalTokens(), r.compressedTokens());
                }
                if (r.compressedTokens() < r.originalTokens()) {
                    any = true;
                    origTotal += r.originalTokens();
                    compTotal += r.compressedTokens();
                    protectedTotal += r.protectedSpans();
                    out.add(new Message(msg.role(), r.text(), msg.toolCalls()));
                } else {
                    out.add(msg);
                }
            }
            if (!any) {
                return request;
            }
            recordMetric(developerId, origTotal, compTotal, protectedTotal);
            return new LlmRequest(request.model(), out, request.maxTokens(), request.temperature());
        } catch (Exception e) {
            log.warn("prompt compression skipped for {}: {}", developerId, e.getMessage());
            return request;
        }
    }

    private void tally(String developerId, CompressionPolicy.Region region, int in, int out) {
        if (region == null) {
            return;
        }
        RegionTally t = regionTallies.computeIfAbsent(developerId + "|" + region.name(),
                k -> new RegionTally());
        t.messages.incrementAndGet();
        t.tokensIn.addAndGet(in);
        t.tokensOut.addAndGet(out);
    }

    private void countSkip(String developerId, String reason) {
        skips.computeIfAbsent(developerId + "|" + reason, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * What the budget controller is doing, per region.
     *
     * <p>The number that matters is the achieved drop per region against the
     * target: a region whose achieved ratio sits far above its target is one
     * where protected spans dominate, which is the compressor correctly refusing
     * to remove content it was told to keep.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> policyStatus(String developerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", policyEnabledFor(developerId));
        out.put("compressionEnabled", enabledFor(developerId));
        out.put("defaultRatio", targetRatio);
        out.put("minTokens", minTokens);
        out.put("shortPromptTokens", CompressionPolicy.SHORT_PROMPT_TOKENS);
        out.put("targets", CompressionPolicy.ratios());

        List<Map<String, Object>> regions = new ArrayList<>();
        for (CompressionPolicy.Region r : CompressionPolicy.Region.values()) {
            RegionTally t = regionTallies.get(developerId + "|" + r.name());
            long in = t == null ? 0 : t.tokensIn.get();
            long comp = t == null ? 0 : t.tokensOut.get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("region", r.name());
            m.put("label", CompressionPolicy.label(r));
            m.put("target", r.keepRatio());
            m.put("messages", t == null ? 0 : t.messages.get());
            m.put("tokensIn", in);
            m.put("tokensOut", comp);
            m.put("achieved", in == 0 ? null : (double) comp / in);
            regions.add(m);
        }
        out.put("regions", regions);

        List<Map<String, Object>> skipped = new ArrayList<>();
        String prefix = developerId + "|";
        skips.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reason", k.substring(prefix.length()));
                m.put("count", v.get());
                skipped.add(m);
            }
        });
        out.put("skipped", skipped);
        return out;
    }

    @Transactional
    public Map<String, Object> configurePolicy(String developerId, Boolean enabled) {
        CompressionPolicySettingEntity cfg = policyRepo.findById(developerId)
                .orElseGet(() -> new CompressionPolicySettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        policyRepo.save(cfg);
        return policyStatus(developerId);
    }

    /** Clears the live per-region tallies. Persisted totals are untouched. */
    public Map<String, Object> resetPolicyTallies(String developerId) {
        regionTallies.keySet().removeIf(k -> k.startsWith(developerId + "|"));
        skips.keySet().removeIf(k -> k.startsWith(developerId + "|"));
        return policyStatus(developerId);
    }

    private void recordMetric(String developerId, int origTotal, int compTotal, int protectedTotal) {
        try {
            double achieved = origTotal == 0 ? 1.0 : (double) compTotal / origTotal;
            metrics.save(new CompressionMetricEntity(developerId, origTotal, compTotal,
                    targetRatio, achieved, protectedTotal));
        } catch (Exception ignored) {
            // Metrics must never break a request.
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(String developerId) {
        List<CompressionMetricEntity> rows = developerId == null || developerId.isBlank()
                ? metrics.findTop200ByOrderByCreatedAtDesc()
                : metrics.findTop200ByDeveloperIdOrderByCreatedAtDesc(developerId);
        long orig = rows.stream().mapToLong(CompressionMetricEntity::getOriginalTokens).sum();
        long comp = rows.stream().mapToLong(CompressionMetricEntity::getCompressedTokens).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", rows.size());
        out.put("originalTokens", orig);
        out.put("compressedTokens", comp);
        out.put("tokensSaved", orig - comp);
        out.put("compressionRatio", comp == 0 ? 1.0 : (double) orig / comp);
        out.put("reductionPct", orig == 0 ? 0 : 1.0 - (double) comp / orig);
        out.put("protectedSpans", rows.stream().mapToInt(CompressionMetricEntity::getProtectedSpans).sum());
        out.put("recent", rows.subList(0, Math.min(30, rows.size())));
        return out;
    }
}
