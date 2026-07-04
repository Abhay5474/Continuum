package io.continuum.api;

import io.continuum.godmode.GodModeService;
import io.continuum.godmode.memory.MemoryEngine;
import io.continuum.godmode.twin.DigitalTwinSimulator;
import io.continuum.persistence.entity.GodModeActionEntity;
import io.continuum.persistence.entity.GodModeConfigEntity;
import io.continuum.persistence.entity.GodModeSimulationEntity;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * God Mode API — session-scoped to the authenticated developer via the existing
 * {@link PortalAuthFilter} (route lives under /api/portal/developer/**).
 * Opt-in, bounded, reversible: DISABLED by default.
 */
@RestController
@RequestMapping("/api/portal/developer/godmode")
public class GodModeController {

    private final GodModeService godMode;
    private final MemoryEngine memory;
    private final DigitalTwinSimulator twin;

    public GodModeController(GodModeService godMode, MemoryEngine memory, DigitalTwinSimulator twin) {
        this.godMode = godMode;
        this.memory = memory;
        this.twin = twin;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    private GodModeConfigEntity requireEnabled(HttpServletRequest req) {
        return godMode.configIfEnabled(dev(req))
                .orElseThrow(() -> new IllegalStateException("God Mode is not enabled"));
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return godMode.status(dev(req));
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(HttpServletRequest req) {
        godMode.setEnabled(dev(req), true);
        return godMode.status(dev(req));
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(HttpServletRequest req) {
        godMode.setEnabled(dev(req), false);
        return godMode.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody SettingsRequest body) {
        godMode.updateSettings(dev(req), body.memactEnabled(), body.twinGateEnabled(),
                body.contextBudgetTokens());
        return godMode.status(dev(req));
    }

    // ---- memory tiers ----

    @PostMapping("/memory/ingest")
    public Map<String, Object> ingest(HttpServletRequest req, @RequestBody IngestRequest body) {
        GodModeConfigEntity c = requireEnabled(req);
        memory.ingest(c, body.sessionId(), body.role(), body.content());
        return memory.tierSnapshot(c);
    }

    @PostMapping("/memory/consolidate")
    public Map<String, Object> consolidate(HttpServletRequest req) {
        return memory.consolidate(requireEnabled(req));
    }

    @PostMapping("/memory/retrieve")
    public List<Map<String, Object>> retrieve(HttpServletRequest req, @RequestBody RetrieveRequest body) {
        return memory.retrieve(requireEnabled(req), body.query(),
                body.limit() == null ? 5 : body.limit());
    }

    @GetMapping("/memory/graph")
    public Map<String, Object> graph(HttpServletRequest req) {
        return memory.experienceGraph(dev(req));
    }

    @DeleteMapping("/memory")
    public Map<String, Object> wipe(HttpServletRequest req) {
        memory.wipe(dev(req));
        return godMode.status(dev(req));
    }

    @GetMapping("/actions")
    public List<GodModeActionEntity> actions(HttpServletRequest req) {
        return memory.recentActions(dev(req));
    }

    // ---- digital twin ----

    @PostMapping("/twin/simulate")
    public GodModeSimulationEntity simulate(HttpServletRequest req, @RequestBody SimulateRequest body) {
        requireEnabled(req);
        DigitalTwinSimulator.Scenario scenario = body.scenario() == null
                ? DigitalTwinSimulator.Scenario.HISTORICAL_REPLAY
                : DigitalTwinSimulator.Scenario.valueOf(body.scenario());
        return twin.simulate(dev(req), body.candidateBundleId(), body.baselineBundleId(), scenario);
    }

    @GetMapping("/twin/simulations")
    public List<GodModeSimulationEntity> simulations(HttpServletRequest req) {
        return twin.recent(dev(req));
    }

    public record SettingsRequest(Boolean memactEnabled, Boolean twinGateEnabled,
                                  Integer contextBudgetTokens) {
    }

    public record IngestRequest(String sessionId, String role, String content) {
    }

    public record RetrieveRequest(String query, Integer limit) {
    }

    public record SimulateRequest(Long candidateBundleId, Long baselineBundleId, String scenario) {
    }
}
