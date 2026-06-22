package io.continuum.api;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ProviderModelHealthEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Feature 8 — Developer Infrastructure observability. */
@RestController
@RequestMapping("/api/gateway")
public class GatewayStatsController {

    private final GatewayRequestLogRepository logs;
    private final ProviderHealthTracker health;

    public GatewayStatsController(GatewayRequestLogRepository logs, ProviderHealthTracker health) {
        this.logs = logs;
        this.health = health;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long success = logs.countBySuccess(true);
        long failed = logs.countBySuccess(false);
        long failoversPrevented = logs.totalFailovers(); // failovers that the developer never saw

        Map<String, Object> providerUsage = new LinkedHashMap<>();
        for (var u : logs.usageByProvider()) {
            providerUsage.put(u.getProvider() == null ? "none" : u.getProvider(),
                    Map.of("requests", u.getRequests(), "cost", u.getCost()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalRequests", success + failed);
        out.put("successful", success);
        out.put("failed", failed);
        out.put("successRate", (success + failed) == 0 ? 1.0 : (double) success / (success + failed));
        out.put("failuresPrevented", failoversPrevented);
        out.put("developerVisibleFailures", failed);
        out.put("providerUsage", providerUsage);
        out.put("totalTokens", logs.totalTokens());
        out.put("totalCostUsd", logs.totalCost());
        return out;
    }

    @GetMapping("/requests")
    public List<GatewayRequestLogEntity> requests(@RequestParam(defaultValue = "100") int limit) {
        return logs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).getContent();
    }

    @GetMapping("/health")
    public List<ProviderModelHealthEntity> health() {
        return health.all();
    }
}
