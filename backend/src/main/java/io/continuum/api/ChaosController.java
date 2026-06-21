package io.continuum.api;

import io.continuum.chaos.ChaosMonkey;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Runtime control plane for fault injection. Drives the chaos demos:
 * force the primary provider down, raise activity failure rates, inject latency,
 * or schedule a simulated worker crash mid-activity.
 */
@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    private final ChaosMonkey chaos;

    public ChaosController(ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public ChaosMonkey.ChaosState state() {
        return chaos.state();
    }

    @PostMapping("/provider-down")
    public ChaosMonkey.ChaosState providerDown(@RequestParam(defaultValue = "true") boolean down) {
        chaos.setPrimaryProviderDown(down);
        return chaos.state();
    }

    @PostMapping("/activity-failure-rate")
    public ChaosMonkey.ChaosState activityFailureRate(@RequestParam double rate) {
        chaos.setActivityFailureRate(rate);
        return chaos.state();
    }

    @PostMapping("/sink-failure-rate")
    public ChaosMonkey.ChaosState sinkFailureRate(@RequestParam double rate) {
        chaos.setSinkFailureRate(rate);
        return chaos.state();
    }

    @PostMapping("/activity-latency")
    public ChaosMonkey.ChaosState activityLatency(@RequestParam long ms) {
        chaos.setActivityLatencyMs(ms);
        return chaos.state();
    }

    @PostMapping("/crash-after")
    public ChaosMonkey.ChaosState crashAfter(@RequestParam int activities) {
        chaos.scheduleCrashAfter(activities);
        return chaos.state();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        chaos.reset();
        return Map.of("reset", true, "state", chaos.state());
    }
}
