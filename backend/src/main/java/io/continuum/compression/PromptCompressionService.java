package io.continuum.compression;

import io.continuum.persistence.entity.CompressionMetricEntity;
import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.repository.CompressionMetricRepository;
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
    private final PromptCompressor compressor = new PromptCompressor();
    private final double targetRatio;
    private final int minTokens;

    public PromptCompressionService(DeveloperAuthRepository devAuth, CompressionMetricRepository metrics,
                                    @Value("${continuum.compression.target-ratio:0.55}") double targetRatio,
                                    @Value("${continuum.compression.min-tokens:60}") int minTokens) {
        this.devAuth = devAuth;
        this.metrics = metrics;
        this.targetRatio = targetRatio;
        this.minTokens = minTokens;
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
            List<Message> out = new ArrayList<>(messages.size());
            int origTotal = 0;
            int compTotal = 0;
            int protectedTotal = 0;
            boolean any = false;
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                // Preserve the most recent user instruction verbatim.
                if (i == lastUserIdx || msg.content() == null) {
                    out.add(msg);
                    continue;
                }
                PromptCompressor.Result r = compressor.compress(msg.content(), targetRatio, minTokens);
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
