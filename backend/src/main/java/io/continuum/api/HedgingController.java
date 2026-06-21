package io.continuum.api;

import io.continuum.hedging.HedgingPolicy;
import io.continuum.hedging.HedgingService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Extension 4 — Tail Latency Hedging API. Toggle hedging and tune its threshold,
 * fan-out cap and per-request cost budget.
 */
@RestController
@RequestMapping("/api/hedging")
public class HedgingController {

    private final HedgingService hedging;

    public HedgingController(HedgingService hedging) {
        this.hedging = hedging;
    }

    @GetMapping
    public Map<String, Object> state() {
        HedgingPolicy p = hedging.getPolicy();
        return Map.of("enabled", hedging.isEnabled(),
                "thresholdMs", p.thresholdMs(),
                "maxHedges", p.maxHedges(),
                "perRequestBudgetUsd", p.perRequestBudgetUsd() == null ? "none" : p.perRequestBudgetUsd());
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(@RequestParam(defaultValue = "true") boolean enabled) {
        hedging.setEnabled(enabled);
        return state();
    }

    @PostMapping("/policy")
    public Map<String, Object> policy(@RequestParam long thresholdMs,
                                      @RequestParam(defaultValue = "1") int maxHedges,
                                      @RequestParam(required = false) Double budgetUsd) {
        hedging.setPolicy(new HedgingPolicy(thresholdMs, maxHedges, budgetUsd));
        return state();
    }
}
