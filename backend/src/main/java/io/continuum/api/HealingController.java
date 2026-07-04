package io.continuum.api;

import io.continuum.healing.ParadoxResolutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V5.1.1 — control-plane monitoring for the Paradox Resolution Engine.
 * The engine itself is compulsory and always on; these endpoints only observe
 * (status, per-instance ledger) or dry-run a validation scan.
 */
@RestController
@RequestMapping("/api/gateway/healing")
public class HealingController {

    private final ParadoxResolutionService healing;

    public HealingController(ParadoxResolutionService healing) {
        this.healing = healing;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return healing.status();
    }

    @GetMapping("/workflow/{workflowId}")
    public Map<String, Object> workflow(@PathVariable String workflowId) {
        return healing.workflowLedger(workflowId);
    }

    /** Dry-run code-to-history validation. Body: {"workflowId": "..."} (optional). */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody(required = false) Map<String, String> body) {
        String workflowId = body == null ? null : body.get("workflowId");
        return healing.verify(workflowId);
    }
}
