package io.continuum.firewall;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.entity.FirewallEventEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.persistence.repository.FirewallEventRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V8 gateway integration for the {@link PromptFirewall}.
 *
 * Gated by {@code developer_auth.v8_firewall_enabled} (OFF by default): when
 * off, {@link #guardInbound}/{@link #guardOutbound} are pass-throughs — the
 * exact legacy path. When on:
 * <ul>
 *   <li>inbound prompts are PII-redacted before they leave for the provider,
 *       and high-confidence prompt-injection attempts throw {@link BlockedException}
 *       (mapped to a clean 4xx by the controller);</li>
 *   <li>outbound responses are scanned for leaked secrets.</li>
 * </ul>
 * Every detection is recorded for the security dashboard.
 */
@Service
public class PromptFirewallService {

    private static final Logger log = LoggerFactory.getLogger(PromptFirewallService.class);

    private final DeveloperAuthRepository devAuth;
    private final FirewallEventRepository events;
    private final PromptFirewall firewall = new PromptFirewall();

    public PromptFirewallService(DeveloperAuthRepository devAuth, FirewallEventRepository events) {
        this.devAuth = devAuth;
        this.events = events;
    }

    /** Thrown when an inbound prompt is blocked for a high-confidence injection attempt. */
    public static class BlockedException extends RuntimeException {
        public BlockedException(String message) {
            super(message);
        }
    }

    public boolean enabledFor(String developerId) {
        return developerId != null && devAuth.findById(developerId)
                .map(DeveloperAuthEntity::isV8FirewallEnabled).orElse(false);
    }

    @Transactional
    public boolean setEnabled(String developerId, boolean enabled) {
        DeveloperAuthEntity auth = devAuth.findById(developerId)
                .orElseThrow(() -> new IllegalStateException("No portal account for developer"));
        auth.setV8FirewallEnabled(enabled);
        devAuth.save(auth);
        log.info("V8 Prompt Firewall {} for developer {}", enabled ? "ENABLED" : "DISABLED", developerId);
        return enabled;
    }

    /**
     * Redact PII from inbound messages and block confident injection attempts.
     * Returns the (possibly redacted) request; throws {@link BlockedException}
     * if blocked. Pass-through when the firewall is off.
     */
    public LlmRequest guardInbound(String developerId, LlmRequest request) {
        if (!enabledFor(developerId) || request == null || request.messages() == null) {
            return request;
        }
        try {
            List<Message> out = new ArrayList<>(request.messages().size());
            for (Message msg : request.messages()) {
                if (msg.content() == null) {
                    out.add(msg);
                    continue;
                }
                PromptFirewall.InboundResult r = firewall.scanInbound(msg.content(), true, true);
                if (r.blocked()) {
                    record(developerId, "INBOUND", "PROMPT_INJECTION", "BLOCKED", 1,
                            "score=" + r.injectionScore() + " hits=" + r.injectionHits());
                    throw new BlockedException("Request blocked: prompt-injection attempt detected");
                }
                if (r.injectionScore() > 0) {
                    record(developerId, "INBOUND", "PROMPT_INJECTION", "FLAGGED", r.injectionHits().size(),
                            "score=" + r.injectionScore());
                }
                for (PromptFirewall.Match m : r.redactions()) {
                    record(developerId, "INBOUND", m.category(), "REDACTED", m.count(), null);
                }
                out.add(r.changed() ? new Message(msg.role(), r.sanitized(), msg.toolCalls()) : msg);
            }
            return new LlmRequest(request.model(), out, request.maxTokens(), request.temperature());
        } catch (BlockedException be) {
            throw be;
        } catch (Exception e) {
            log.warn("inbound firewall skipped for {}: {}", developerId, e.getMessage());
            return request;
        }
    }

    /** Scan an outbound response for leaked secrets. Pass-through when off. */
    public String guardOutbound(String developerId, String content) {
        if (!enabledFor(developerId) || content == null || content.isBlank()) {
            return content;
        }
        try {
            PromptFirewall.OutboundResult r = firewall.scanOutbound(content, true);
            for (PromptFirewall.Match m : r.redactions()) {
                record(developerId, "OUTBOUND", m.category(), "REDACTED", m.count(), null);
            }
            return r.sanitized();
        } catch (Exception e) {
            log.warn("outbound firewall skipped for {}: {}", developerId, e.getMessage());
            return content;
        }
    }

    private void record(String developerId, String direction, String category,
                        String action, int count, String detail) {
        try {
            events.save(new FirewallEventEntity(developerId, direction, category, action, count, detail));
        } catch (Exception ignored) {
            // Auditing must never break a request.
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(String developerId) {
        List<FirewallEventEntity> rows = developerId == null || developerId.isBlank()
                ? events.findTop200ByOrderByCreatedAtDesc()
                : events.findTop200ByDeveloperIdOrderByCreatedAtDesc(developerId);
        Map<String, Long> byCategory = new LinkedHashMap<>();
        long redacted = 0;
        long blocked = 0;
        long flagged = 0;
        for (FirewallEventEntity e : rows) {
            byCategory.merge(e.getCategory(), (long) e.getMatchCount(), Long::sum);
            switch (e.getAction()) {
                case "REDACTED" -> redacted += e.getMatchCount();
                case "BLOCKED" -> blocked++;
                case "FLAGGED" -> flagged++;
                default -> { }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", rows.size());
        out.put("piiRedacted", redacted);
        out.put("injectionsBlocked", blocked);
        out.put("injectionsFlagged", flagged);
        out.put("byCategory", byCategory);
        out.put("recent", rows.subList(0, Math.min(40, rows.size())));
        return out;
    }
}
