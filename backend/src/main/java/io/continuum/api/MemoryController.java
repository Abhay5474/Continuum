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
        MemoryTier tier = req.tier() == null ? MemoryTier.EPISODIC : MemoryTier.valueOf(req.tier());
        var saved = memory.store(scoped(http, req.scope()), tier, req.content(),
                req.salience() == null ? 0.5 : req.salience());
        return Map.of("id", saved.getId(), "tier", saved.getTier().name());
    }

    @PostMapping("/retrieve")
    public List<MemoryService.RetrievedMemory> retrieve(@RequestBody RetrieveRequest req, HttpServletRequest http) {
        return memory.retrieve(scoped(http, req.scope()), req.query(), req.topK() == null ? 5 : req.topK());
    }

    @PostMapping("/context")
    public MemoryService.ContextResult context(@RequestBody ContextRequest req, HttpServletRequest http) {
        return memory.buildContext(scoped(http, req.scope()), req.query(),
                req.topK() == null ? 5 : req.topK(), req.maxChars() == null ? 2000 : req.maxChars());
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

    public record StoreRequest(String scope, String tier, String content, Double salience) {
    }

    public record RetrieveRequest(String scope, String query, Integer topK) {
    }

    public record ContextRequest(String scope, String query, Integer topK, Integer maxChars) {
    }
}
