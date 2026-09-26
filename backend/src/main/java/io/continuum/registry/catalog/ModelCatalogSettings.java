package io.continuum.registry.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * How often the catalogue talks to the providers, and how much.
 *
 * <p>Models live for months, so the scheduled check is rare: due ten days after
 * the last successful one, about three times a month. "Due" is measured from the
 * last success rather than set on calendar dates, so a deployment that was off
 * on a check day catches up when it next runs instead of skipping a month.
 */
@Component
public class ModelCatalogSettings {

    @Value("${continuum.models.check-interval-days:10}")
    private int checkIntervalDays;

    /** After a failed check (provider down, network), wait this long before trying again. */
    @Value("${continuum.models.retry-after-failure-hours:6}")
    private int retryAfterFailureHours;

    /** The "Check now" button: at most one check per this many minutes, engine-wide. */
    @Value("${continuum.models.manual-cooldown-minutes:10}")
    private int manualCooldownMinutes;

    /** Test calls per provider per check. Untested models stay unrouted and wait for the next check. */
    @Value("${continuum.models.max-probes-per-provider:12}")
    private int maxProbesPerProvider;

    /** Gap between test calls, kept well inside free-tier requests-per-minute limits. */
    @Value("${continuum.models.probe-spacing-ms.groq:2500}")
    private long groqSpacingMs;

    @Value("${continuum.models.probe-spacing-ms.gemini:6000}")
    private long geminiSpacingMs;

    /** A request reporting a model gone triggers at most one confirming list per provider per this window. */
    @Value("${continuum.models.confirm-cooldown-minutes:30}")
    private int confirmCooldownMinutes;

    /** A model that could not be used is tested again after this long, since free tiers change. */
    @Value("${continuum.models.reprobe-after-days:30}")
    private int reprobeAfterDays;

    /** How long a model reported gone is kept out of routing while the report is confirmed. */
    @Value("${continuum.models.quarantine-hours:6}")
    private int quarantineHours;

    public Duration checkInterval() {
        return Duration.ofDays(Math.max(1, checkIntervalDays));
    }

    public Duration retryAfterFailure() {
        return Duration.ofHours(Math.max(1, retryAfterFailureHours));
    }

    public Duration manualCooldown() {
        return Duration.ofMinutes(Math.max(1, manualCooldownMinutes));
    }

    public int maxProbesPerProvider() {
        return Math.max(0, Math.min(50, maxProbesPerProvider));
    }

    public long probeSpacingMs(String provider) {
        return Math.max(0, "gemini".equals(provider) ? geminiSpacingMs : groqSpacingMs);
    }

    public Duration confirmCooldown() {
        return Duration.ofMinutes(Math.max(1, confirmCooldownMinutes));
    }

    public Duration reprobeAfter() {
        return Duration.ofDays(Math.max(1, reprobeAfterDays));
    }

    public Duration quarantine() {
        return Duration.ofHours(Math.max(1, quarantineHours));
    }

    /** For tests: every limit in one place. */
    public static ModelCatalogSettings of(int intervalDays, int maxProbes, long spacingMs) {
        ModelCatalogSettings s = new ModelCatalogSettings();
        s.checkIntervalDays = intervalDays;
        s.retryAfterFailureHours = 6;
        s.manualCooldownMinutes = 10;
        s.maxProbesPerProvider = maxProbes;
        s.groqSpacingMs = spacingMs;
        s.geminiSpacingMs = spacingMs;
        s.confirmCooldownMinutes = 30;
        s.reprobeAfterDays = 30;
        s.quarantineHours = 6;
        return s;
    }
}
