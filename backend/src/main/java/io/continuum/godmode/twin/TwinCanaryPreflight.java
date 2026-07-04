package io.continuum.godmode.twin;

import io.continuum.autopilot.CanaryPreflight;
import io.continuum.godmode.GodModeService;
import io.continuum.persistence.entity.GodModeConfigEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * God Mode's contribution to the V4 canary path: before a candidate bundle
 * receives ANY live traffic, replay it against the developer's historical
 * traffic in the digital twin. A confident offline regression (the same
 * Bayesian rules the live canary uses) vetoes the canary outright.
 *
 * Strictly gated: developers who did not enable God Mode (or its twin gate)
 * always get {@code allow()} — the V4 canary path is bit-for-bit unchanged.
 */
@Component
public class TwinCanaryPreflight implements CanaryPreflight {

    private static final Logger log = LoggerFactory.getLogger(TwinCanaryPreflight.class);

    private final GodModeService godMode;
    private final DigitalTwinSimulator twin;

    public TwinCanaryPreflight(GodModeService godMode, DigitalTwinSimulator twin) {
        this.godMode = godMode;
        this.twin = twin;
    }

    @Override
    public Result check(String developerId, Long candidateBundleId) {
        var config = godMode.configIfEnabled(developerId)
                .filter(GodModeConfigEntity::isTwinGateEnabled);
        if (config.isEmpty()) {
            return Result.allow(); // God Mode off ⇒ exact V4 behavior
        }
        try {
            var sim = twin.simulate(developerId, candidateBundleId, null,
                    DigitalTwinSimulator.Scenario.HISTORICAL_REPLAY);
            if ("ROLLBACK".equals(sim.getVerdict())) {
                log.warn("Digital twin vetoed canary for {} bundle {}: {}",
                        developerId, candidateBundleId, sim.getReason());
                return new Result(true, "Digital twin: " + sim.getReason(), sim.getConfidence());
            }
            return new Result(false, "Digital twin: " + sim.getReason(), sim.getConfidence());
        } catch (Exception e) {
            // The twin is advisory; a twin failure must never block V4.
            log.warn("digital twin pre-flight skipped: {}", e.getMessage());
            return Result.allow();
        }
    }
}
