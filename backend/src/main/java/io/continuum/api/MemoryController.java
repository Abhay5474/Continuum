package io.continuum.api;

import io.continuum.memory.MemoryService;
import io.continuum.memory.MemoryTier;
import io.continuum.persistence.entity.MemoryEntryEntity;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Long-context memory API.
 *
 * <p>The {@code scope} is a free-form string chosen by the caller, so on its own it
 * is not an isolation boundary — any tenant could read another tenant's memory by
 * guessing the name. Scopes are therefore namespaced with the signed-in developer
 * id before touching storage ({@code dev_x::agent-1}). Callers still see and pass
 * their own plain scope names; isolation is enforced server-side.
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    /** Prefixes the caller's scope with their tenant id so scopes cannot collide or leak. */
    private String scoped(HttpServletRequest req, String scope) {
        String safe = (scope == null || scope.isBlank()) ? "default" : scope;
        String dev = RequestScope.developerId(req);
        return dev == null ? safe : dev + "::" + safe;
    }

    @PostMapping("/store")
    public Map<String, Object> store(@RequestBody StoreRequest req, HttpServletRequest http) {
        // Checked here rather than left to the database: a missing content used
        // to reach the NOT NULL constraint and come back as a 409 "conflict".
        if (req.content() == null || req.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (req.content().length() > MAX_CONTENT) {
            throw new IllegalArgumentException("content is limited to " + MAX_CONTENT + " characters");
        }
        var saved = memory.store(scoped(http, req.scope()), tierOf(req.tier()), req.content(),
                req.salience() == null ? 0.5 : Math.max(0, Math.min(1, req.salience())));
        return Map.of("id", saved.getId(), "tier", saved.getTier().name());
    }

    @PostMapping("/retrieve")
    public List<MemoryService.RetrievedMemory> retrieve(@RequestBody RetrieveRequest req, HttpServletRequest http) {
        return memory.retrieve(scoped(http, req.scope()), req.query() == null ? "" : req.query(), topK(req.topK()));
    }

    @PostMapping("/context")
    public MemoryService.ContextResult context(@RequestBody ContextRequest req, HttpServletRequest http) {
        return memory.buildContext(scoped(http, req.scope()), req.query() == null ? "" : req.query(),
                topK(req.topK()), req.maxChars() == null ? 2000 : Math.max(100, Math.min(50_000, req.maxChars())));
    }

    @PostMapping("/compress")
    public Object compress(@RequestParam String scope, @RequestParam(defaultValue = "false") boolean useLlm,
                           HttpServletRequest http) {
        MemoryEntryEntity summary = memory.compress(scoped(http, scope), useLlm);
        return summary == null ? Map.of("compressed", false, "reason", "not enough episodic memories")
                : Map.of("compressed", true, "summaryId", summary.getId(), "summary", summary.getSummary());
    }

    @PostMapping("/maintain")
    public Map<String, Object> maintain(@RequestParam String scope, HttpServletRequest http) {
        return Map.of("scope", scope, "tierChanges", memory.maintain(scoped(http, scope)));
    }

    @GetMapping("/{scope}")
    public List<MemoryEntryEntity> list(@PathVariable String scope, HttpServletRequest http) {
        return memory.list(scoped(http, scope));
    }

    private static final int MAX_CONTENT = 20_000;

    private static int topK(Integer k) {
        return k == null ? 5 : Math.max(1, Math.min(100, k));
    }

    private static MemoryTier tierOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryTier.EPISODIC;
        }
        try {
            return MemoryTier.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tier must be one of " + java.util.Arrays.toString(MemoryTier.values()));
        }
    }

    public record StoreRequest(String scope, String tier, String content, Double salience) {
    }

    public record RetrieveRequest(String scope, String query, Integer topK) {
    }

    public record ContextRequest(String scope, String query, Integer topK, Integer maxChars) {
    }
}
