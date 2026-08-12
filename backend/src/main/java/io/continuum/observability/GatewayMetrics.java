package io.continuum.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * What the gateway publishes to Prometheus.
 *
 * <p>The console is a good screen and the wrong place for this to live alone: an
 * infrastructure product whose telemetry is only visible inside its own UI is
 * asking every team to watch a second dashboard, and nobody's on-call rotation
 * points at it. These are the same facts, on the endpoint their existing
 * alerting already scrapes.
 *
 * <p><b>Overhead is measured, not estimated.</b> The first question anyone asks
 * about a gateway is what it costs them in latency, and until now the honest
 * answer was that nobody knew. {@code continuum.gateway.overhead} is the time
 * spent inside Continuum with the provider call subtracted — routing, admission,
 * the firewall, the cache lookup, everything this product does — so the answer
 * is a number a buyer can read off their own Grafana rather than take on trust.
 *
 * <p>Cardinality is kept deliberately low: provider, model and outcome, and
 * nothing per-developer. A tenant label on a metric is how a Prometheus falls
 * over three months after the feature that added it shipped.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** End-to-end wall time for a gateway request, tagged by how it ended. */
    public void request(String provider, String model, boolean success, long totalMs) {
        Timer.builder("continuum.gateway.request")
                .description("End-to-end gateway request duration")
                .tag("provider", safe(provider))
                .tag("model", safe(model))
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .record(totalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Time spent in Continuum rather than in the provider.
     *
     * <p>Clamped at zero: the two clocks are read at slightly different points
     * and a negative overhead is a measurement artefact, not a discovery. It is
     * better for the metric to say "no measurable overhead" than to publish a
     * number that cannot be true.
     */
    public void overhead(String provider, long totalMs, long providerMs) {
        Timer.builder("continuum.gateway.overhead")
                .description("Latency added by Continuum, excluding the provider call")
                .tag("provider", safe(provider))
                .register(registry)
                .record(Math.max(0, totalMs - providerMs), TimeUnit.MILLISECONDS);
    }

    /** A provider failure the engine absorbed by re-routing. */
    public void failover(String from, String reason) {
        Counter.builder("continuum.gateway.failover")
                .description("Provider failures absorbed by re-routing")
                .tag("provider", safe(from))
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    /** A request the caller had to deal with itself. Should stay at zero. */
    public void visibleFailure(String reason) {
        Counter.builder("continuum.gateway.visible_failure")
                .description("Failures that reached the caller")
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    public void cache(boolean hit) {
        Counter.builder("continuum.cache.lookup")
                .description("Semantic cache lookups")
                .tag("result", hit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    public void tokens(String provider, int prompt, int completion) {
        Counter.builder("continuum.tokens").tag("provider", safe(provider)).tag("kind", "prompt")
                .register(registry).increment(prompt);
        Counter.builder("continuum.tokens").tag("provider", safe(provider)).tag("kind", "completion")
                .register(registry).increment(completion);
    }

    public void cost(String provider, double usd) {
        Counter.builder("continuum.cost.usd")
                .description("Provider spend")
                .tag("provider", safe(provider))
                .register(registry)
                .increment(usd);
    }

    /**
     * Bounds a tag value.
     *
     * <p>An unbounded label is the standard way to take a Prometheus down, and
     * the obvious source here is a provider error string with an id in it.
     */
    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.length() > 48 ? value.substring(0, 48) : value;
        return trimmed.replace('\n', ' ').replace('"', '\'');
    }
}
