package io.continuum.drift;

import io.continuum.persistence.entity.BreakerEventEntity;
import io.continuum.persistence.entity.BreakerSettingEntity;
import io.continuum.persistence.entity.BreakerStateEntity;
import io.continuum.persistence.repository.BreakerEventRepository;
import io.continuum.persistence.repository.BreakerSettingRepository;
import io.continuum.persistence.repository.BreakerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trips a model out of rotation when its <em>answers</em> get worse.
 *
 * <p>Continuum already has a health tracker, and it watches the things a
 * classical circuit breaker watches: errors, timeouts, latency. The failure mode
 * that actually damages a production AI application is the one none of those
 * see — the provider is up, fast, returning 200s, and quietly worse. A silent
 * model revision, a quantisation change, a new safety filter refusing benign
 * requests. Chen, Zaharia and Zou measured GPT-4's accuracy on one task falling
 * from 84% to 51% between two dates on an unchanged API. A latency dashboard
 * stays green through all of that.
 *
 * <p>The inputs cost nothing extra: the quality gate already scores every answer
 * and the uncertainty service already measures agreement. This turns those
 * verdicts into a control decision.
 *
 * <p><b>Scoped per tenant, deliberately.</b> Drift is a property of the provider,
 * so pooling observations across accounts would detect it sooner. It would also
 * mean one account's traffic could trip a breaker that reroutes everybody
 * else's — a denial-of-service primitive dressed as a feature. Each account
 * observes its own traffic and trips its own breaker.
 *
 * <p>Recovery is probe-based rather than timed. After the cooldown the breaker
 * goes half-open and lets a single request through; the model earns its way back
 * by answering well, and re-opens immediately if it does not. Closing on a timer
 * alone would send full traffic back to a model nothing has re-checked.
 */
@Service
public class SemanticBreakerService {

    private static final Logger log = LoggerFactory.getLogger(SemanticBreakerService.class);

    /** How many recent scores to keep per breaker for the console's trace. */
    private static final int TRACE = 60;

    private final BreakerSettingRepository settings;
    private final BreakerStateRepository states;
    private final BreakerEventRepository events;

    /** Detectors are in-memory: a baseline is cheap to relearn, and stale ones mislead. */
    private final Map<String, CusumDetector> detectors = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>> traces = new ConcurrentHashMap<>();

    public SemanticBreakerService(BreakerSettingRepository settings, BreakerStateRepository states,
                                  BreakerEventRepository events) {
        this.settings = settings;
        this.states = states;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public BreakerSettingEntity settingsFor(String developerId) {
        return settings.findById(developerId).orElseGet(() -> new BreakerSettingEntity(developerId));
    }

    @Transactional(readOnly = true)
    public boolean enabledFor(String developerId) {
        return developerId != null && settingsFor(developerId).isEnabled();
    }

    /**
     * Whether this model may serve a request right now.
     *
     * <p>An open breaker whose cooldown has elapsed flips to half-open and
     * permits exactly this one request, so recovery is tested by real traffic
     * rather than assumed.
     */
    @Transactional
    public boolean allows(String developerId, String provider, String model) {
        if (!enabledFor(developerId)) {
            return true;
        }
        var state = states.findByDeveloperIdAndProviderAndModel(developerId, provider, model).orElse(null);
        if (state == null || state.getState() == BreakerStateEntity.State.CLOSED) {
            return true;
        }
        if (state.getState() == BreakerStateEntity.State.HALF_OPEN) {
            // A probe is already in flight; anything else stays diverted.
            return false;
        }
        int cooldown = settingsFor(developerId).getCooldownSeconds();
        if (state.getOpenedAt() != null
                && Duration.between(state.getOpenedAt(), Instant.now()).getSeconds() >= cooldown) {
            state.halfOpen();
            states.save(state);
            record(developerId, provider, model, "PROBING", state.getBaseline(), null,
                    state.getAccumulated(), "cooldown elapsed — letting one request through to test recovery");
            return true;
        }
        return false;
    }

    /** Models this tenant's breakers are currently diverting traffic away from. */
    @Transactional(readOnly = true)
    public List<String> openModels(String developerId) {
        if (!enabledFor(developerId)) {
            return List.of();
        }
        return states.findByDeveloperId(developerId).stream()
                .filter(s -> s.getState() != BreakerStateEntity.State.CLOSED)
                .map(s -> s.getProvider() + "/" + s.getModel())
                .toList();
    }

    /**
     * Feeds one quality observation in, and trips or recovers accordingly.
     *
     * <p>Never throws. A monitor that can fail a request is worse than no
     * monitor.
     *
     * @param score a quality signal in [0,1] — the gate's verdict, or agreement
     */
    @Transactional
    public void observe(String developerId, String provider, String model, double score, String detail) {
        if (!enabledFor(developerId) || provider == null || model == null) {
            return;
        }
        try {
            BreakerSettingEntity cfg = settingsFor(developerId);
            String key = developerId + "|" + provider + "|" + model;
            CusumDetector d = detectors.computeIfAbsent(key,
                    k -> new CusumDetector(cfg.getWarmup(), cfg.getSlack(), cfg.getThreshold()));
            traces.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(score);
            Deque<Double> trace = traces.get(key);
            while (trace.size() > TRACE) {
                trace.removeFirst();
            }

            CusumDetector.State result = d.observe(score);
            BreakerStateEntity state = states.findByDeveloperIdAndProviderAndModel(developerId, provider, model)
                    .orElseGet(() -> states.save(new BreakerStateEntity(developerId, provider, model)));
            state.snapshot(Double.isNaN(d.baseline()) ? null : d.baseline(), d.mean(),
                    d.accumulated(), d.count());

            if (state.getState() == BreakerStateEntity.State.HALF_OPEN) {
                // The probe answered. Judge it against the baseline it fell from.
                boolean recovered = !Double.isNaN(d.baseline())
                        && score >= d.baseline() - cfg.getSlack();
                if (recovered) {
                    d.reset();
                    state.close();
                    record(developerId, provider, model, "RECOVERED", d.baseline(), score, 0.0,
                            String.format("probe scored %.2f against a baseline of %.2f", score, d.baseline()));
                } else {
                    state.open();
                    record(developerId, provider, model, "REOPENED", d.baseline(), score, d.accumulated(),
                            String.format("probe scored %.2f, still below the %.2f baseline",
                                    score, d.baseline()));
                }
                states.save(state);
                return;
            }

            if (result == CusumDetector.State.DRIFTED
                    && state.getState() == BreakerStateEntity.State.CLOSED) {
                state.open();
                record(developerId, provider, model, "TRIPPED", d.baseline(), score, d.accumulated(),
                        String.format("quality fell from a baseline of %.2f to a recent mean of %.2f; "
                                        + "accumulated shortfall %.2f crossed the %.2f threshold%s",
                                d.baseline(), d.mean(), d.accumulated(), d.threshold(),
                                detail == null ? "" : " — " + detail));
                log.warn("Semantic breaker OPEN for {}/{} (tenant {}): baseline {} recent {}",
                        provider, model, developerId, d.baseline(), d.mean());
            }
            states.save(state);
        } catch (Exception e) {
            log.debug("Breaker observation failed: {}", e.getMessage());
        }
    }

    private void record(String developerId, String provider, String model, String kind,
                        Double baseline, Double observed, Double accumulated, String detail) {
        try {
            events.save(new BreakerEventEntity(developerId, provider, model, kind,
                    baseline, observed, accumulated, detail));
        } catch (Exception ignored) {
            // An event we could not write must not undo the transition itself.
        }
    }

    // --- operations ----------------------------------------------------------

    /** Manually closes a breaker and forgets its baseline, so it relearns. */
    @Transactional
    public Map<String, Object> reset(String developerId, String provider, String model) {
        states.findByDeveloperIdAndProviderAndModel(developerId, provider, model).ifPresent(s -> {
            s.close();
            states.save(s);
        });
        CusumDetector d = detectors.get(developerId + "|" + provider + "|" + model);
        if (d != null) {
            d.rebaseline();
        }
        record(developerId, provider, model, "RECOVERED", null, null, 0.0, "manually reset");
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled, Integer warmup,
                                         Double slack, Double threshold, Integer cooldownSeconds) {
        BreakerSettingEntity cfg = settingsFor(developerId);
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        if (warmup != null) {
            cfg.setWarmup(warmup);
        }
        if (slack != null) {
            cfg.setSlack(slack);
        }
        if (threshold != null) {
            cfg.setThreshold(threshold);
        }
        if (cooldownSeconds != null) {
            cfg.setCooldownSeconds(cooldownSeconds);
        }
        settings.save(cfg);
        // Detector parameters are baked in at construction, so a settings change
        // has to discard them or the new values would silently not apply.
        detectors.keySet().removeIf(k -> k.startsWith(developerId + "|"));
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        states.deleteByDeveloperId(developerId);
        events.deleteByDeveloperId(developerId);
        detectors.keySet().removeIf(k -> k.startsWith(developerId + "|"));
        traces.keySet().removeIf(k -> k.startsWith(developerId + "|"));
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        BreakerSettingEntity cfg = settingsFor(developerId);
        List<BreakerStateEntity> rows = states.findByDeveloperId(developerId);

        List<Map<String, Object>> breakers = new ArrayList<>();
        for (BreakerStateEntity s : rows) {
            String key = developerId + "|" + s.getProvider() + "|" + s.getModel();
            CusumDetector d = detectors.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", s.getProvider());
            m.put("model", s.getModel());
            m.put("state", s.getState().name());
            m.put("baseline", s.getBaseline());
            m.put("recentMean", s.getRecentMean());
            m.put("accumulated", s.getAccumulated());
            // How close to firing, which is the thing worth watching before it does.
            m.put("pressure", d == null ? 0.0 : d.pressure());
            m.put("warm", d != null && d.warm());
            m.put("observations", s.getObservations());
            m.put("warmup", cfg.getWarmup());
            m.put("trips", s.getTrips());
            m.put("openedAt", s.getOpenedAt());
            m.put("trace", traces.getOrDefault(key, new ArrayDeque<>()).stream().toList());
            breakers.add(m);
        }
        breakers.sort((a, b) -> {
            int rank = rank((String) a.get("state")) - rank((String) b.get("state"));
            return rank != 0 ? rank
                    : Double.compare((double) b.get("pressure"), (double) a.get("pressure"));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("warmup", cfg.getWarmup());
        out.put("slack", cfg.getSlack());
        out.put("threshold", cfg.getThreshold());
        out.put("cooldownSeconds", cfg.getCooldownSeconds());
        out.put("breakers", breakers);
        out.put("open", rows.stream().filter(s -> s.getState() == BreakerStateEntity.State.OPEN).count());
        out.put("totalTrips", rows.stream().mapToInt(BreakerStateEntity::getTrips).sum());
        return out;
    }

    /** Open first, then probing, then healthy — the console reads top-down. */
    private static int rank(String state) {
        return switch (state) {
            case "OPEN" -> 0;
            case "HALF_OPEN" -> 1;
            default -> 2;
        };
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentEvents(String developerId, int limit) {
        return events.recentFor(developerId, PageRequest.of(0, Math.max(1, Math.min(100, limit))))
                .stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("provider", e.getProvider());
                    m.put("model", e.getModel());
                    m.put("kind", e.getKind());
                    m.put("baseline", e.getBaseline());
                    m.put("observed", e.getObserved());
                    m.put("accumulated", e.getAccumulated());
                    m.put("detail", e.getDetail());
                    m.put("createdAt", e.getCreatedAt());
                    return m;
                }).toList();
    }
}
