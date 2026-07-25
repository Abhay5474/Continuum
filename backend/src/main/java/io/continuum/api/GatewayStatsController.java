package io.continuum.api;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ProviderModelHealthEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway observability for the console.
 *
 * <p>Every figure is scoped to the signed-in developer, so the dashboard shows
 * <em>your</em> traffic rather than the engine-wide totals it used to report (which
 * both leaked cross-tenant volume and made the numbers meaningless per account).
 * Only an operator session sees engine-wide aggregates.
 */
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
    public Map<String, Object> stats(HttpServletRequest req) {
        String dev = RequestScope.developerId(req);
        boolean scoped = dev != null;

        long success = scoped ? logs.countByDeveloperIdAndSuccess(dev, true) : logs.countBySuccess(true);
        long failed = scoped ? logs.countByDeveloperIdAndSuccess(dev, false) : logs.countBySuccess(false);
        long failoversPrevented = scoped ? logs.totalFailoversForDeveloper(dev) : logs.totalFailovers();

        Map<String, Object> providerUsage = new LinkedHashMap<>();
        var usage = scoped ? logs.usageByProviderForDeveloper(dev) : logs.usageByProvider();
        for (var u : usage) {
            providerUsage.put(u.getProvider() == null ? "none" : u.getProvider(),
                    Map.of("requests", u.getRequests(), "cost", u.getCost()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scoped ? "developer" : "engine");
        out.put("totalRequests", success + failed);
        out.put("successful", success);
        out.put("failed", failed);
        out.put("successRate", (success + failed) == 0 ? 1.0 : (double) success / (success + failed));
        out.put("failuresPrevented", failoversPrevented);
        out.put("developerVisibleFailures", failed);
        out.put("providerUsage", providerUsage);
        out.put("totalTokens", scoped ? logs.totalTokensForDeveloper(dev) : logs.totalTokens());
        out.put("totalCostUsd", scoped ? logs.totalCostForDeveloper(dev) : logs.totalCost());
        return out;
    }

    @GetMapping("/requests")
    public List<GatewayRequestLogEntity> requests(@RequestParam(defaultValue = "100") int limit,
                                                  HttpServletRequest req) {
        int capped = Math.max(1, Math.min(limit, 500));
        String dev = RequestScope.developerId(req);
        var page = PageRequest.of(0, capped);
        return dev == null
                ? logs.findAllByOrderByCreatedAtDesc(page).getContent()
                : logs.findByDeveloperIdOrderByCreatedAtDesc(dev, page).getContent();
    }

    /** Provider health is engine-level infrastructure status, identical for every tenant. */
    @GetMapping("/health")
    public List<ProviderModelHealthEntity> health() {
        return health.all();
    }
}
