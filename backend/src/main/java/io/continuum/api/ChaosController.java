package io.continuum.api;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Runtime control plane for fault injection: force the primary provider down,
 * raise activity failure rates, inject latency, or schedule a simulated worker
 * crash mid-activity.
 *
 * <p>Every call here addresses the caller's own fault profile. A developer arms
 * faults for their own traffic; only the engine operator can arm the global
 * profile. Before this, the switches were shared, so one tenant running a drill
 * degraded everyone.
 */
@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    private final ChaosMonkey chaos;

    public ChaosController(ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    /** Null for an operator (the engine-wide profile), otherwise the tenant's own. */
    private String scope(HttpServletRequest req) {
        return RequestScope.isOperator(req) ? null : RequestScope.requireDeveloper(req);
    }

    @GetMapping
    public ChaosMonkey.ChaosState state(HttpServletRequest req) {
        return chaos.state(scope(req));
    }

    @PostMapping("/provider-down")
    public ChaosMonkey.ChaosState providerDown(@RequestParam(defaultValue = "true") boolean down,
                                               HttpServletRequest req) {
        String s = scope(req);
        chaos.setPrimaryProviderDown(s, down);
        return chaos.state(s);
    }

    /**
     * Arm an intermittent provider failure.
     *
     * <p>The failure mode this product exists for. `provider-down` proves the
     * cleanly-gone case; this proves the far more common one, where a provider
     * answers most calls and drops the rest.
     */
    @PostMapping("/provider-failure-rate")
    public ChaosMonkey.ChaosState providerFailureRate(@RequestParam double rate, HttpServletRequest req) {
        String s = scope(req);
        chaos.setProviderFailureRate(s, rate);
        return chaos.state(s);
    }

    @PostMapping("/activity-failure-rate")
    public ChaosMonkey.ChaosState activityFailureRate(@RequestParam double rate, HttpServletRequest req) {
        String s = scope(req);
        chaos.setActivityFailureRate(s, rate);
        return chaos.state(s);
    }

    @PostMapping("/sink-failure-rate")
    public ChaosMonkey.ChaosState sinkFailureRate(@RequestParam double rate, HttpServletRequest req) {
        String s = scope(req);
        chaos.setSinkFailureRate(s, rate);
        return chaos.state(s);
    }

    @PostMapping("/activity-latency")
    public ChaosMonkey.ChaosState activityLatency(@RequestParam long ms, HttpServletRequest req) {
        String s = scope(req);
        chaos.setActivityLatencyMs(s, ms);
        return chaos.state(s);
    }

    @PostMapping("/crash-after")
    public ChaosMonkey.ChaosState crashAfter(@RequestParam int activities, HttpServletRequest req) {
        String s = scope(req);
        chaos.scheduleCrashAfter(s, activities);
        return chaos.state(s);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest req) {
        String s = scope(req);
        chaos.reset(s);
        return Map.of("reset", true, "state", chaos.state(s));
    }
}
