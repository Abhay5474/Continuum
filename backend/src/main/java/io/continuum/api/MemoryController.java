package io.continuum.api;

import io.continuum.memory.MemoryService;
import io.continuum.memory.MemoryTier;
import io.continuum.persistence.entity.MemoryEntryEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Extension 5 — Long Context Memory API.
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    @PostMapping("/store")
    public Map<String, Object> store(@RequestBody StoreRequest req) {
        MemoryTier tier = req.tier() == null ? MemoryTier.EPISODIC : MemoryTier.valueOf(req.tier());
        var saved = memory.store(req.scope(), tier, req.content(), req.salience() == null ? 0.5 : req.salience());
        return Map.of("id", saved.getId(), "tier", saved.getTier().name());
    }

    @PostMapping("/retrieve")
    public List<MemoryService.RetrievedMemory> retrieve(@RequestBody RetrieveRequest req) {
        return memory.retrieve(req.scope(), req.query(), req.topK() == null ? 5 : req.topK());
    }

    @PostMapping("/context")
    public MemoryService.ContextResult context(@RequestBody ContextRequest req) {
        return memory.buildContext(req.scope(), req.query(),
                req.topK() == null ? 5 : req.topK(), req.maxChars() == null ? 2000 : req.maxChars());
    }

    @PostMapping("/compress")
    public Object compress(@RequestParam String scope, @RequestParam(defaultValue = "false") boolean useLlm) {
        MemoryEntryEntity summary = memory.compress(scope, useLlm);
        return summary == null ? Map.of("compressed", false, "reason", "not enough episodic memories")
                : Map.of("compressed", true, "summaryId", summary.getId(), "summary", summary.getSummary());
    }

    @PostMapping("/maintain")
    public Map<String, Object> maintain(@RequestParam String scope) {
        return Map.of("scope", scope, "tierChanges", memory.maintain(scope));
    }

    @GetMapping("/{scope}")
    public List<MemoryEntryEntity> list(@PathVariable String scope) {
        return memory.list(scope);
    }

    public record StoreRequest(String scope, String tier, String content, Double salience) {
    }

    public record RetrieveRequest(String scope, String query, Integer topK) {
    }

    public record ContextRequest(String scope, String query, Integer topK, Integer maxChars) {
    }
}
