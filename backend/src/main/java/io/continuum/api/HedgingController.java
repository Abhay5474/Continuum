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
                "adaptive", p.adaptive(),
                "hedgeRateCap", p.hedgeRateCap(),
                "minThresholdMs", p.minThresholdMs(),
                "perRequestBudgetUsd", p.perRequestBudgetUsd() == null ? "none" : p.perRequestBudgetUsd());
    }

    /** Live adaptive-hedging metrics (p50/p95/p99, observed hedge rate). */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return hedging.metrics();
    }

    /** Full adaptive policy control (p95-trigger + rate cap), faithful to The Tail at Scale. */
    @PostMapping("/policy/adaptive")
    public Map<String, Object> adaptivePolicy(@RequestParam(defaultValue = "true") boolean adaptive,
                                              @RequestParam(defaultValue = "0.05") double hedgeRateCap,
                                              @RequestParam(defaultValue = "800") long thresholdMs,
                                              @RequestParam(defaultValue = "50") long minThresholdMs,
                                              @RequestParam(defaultValue = "1") int maxHedges,
                                              @RequestParam(required = false) Double budgetUsd) {
        hedging.setPolicy(new HedgingPolicy(thresholdMs, maxHedges, budgetUsd,
                adaptive, hedgeRateCap, minThresholdMs));
        return state();
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
