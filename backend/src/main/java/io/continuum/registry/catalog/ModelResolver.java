package io.continuum.registry.catalog;

import io.continuum.config.LlmProperties;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.ModelRepository;
import io.continuum.registry.ModelStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which model a request to a provider actually runs on.
 *
 * <p>Model names used to be fixed in configuration, so when Groq retired the one
 * configured, every request that did not name a model failed. Now:
 * <ul>
 *   <li>a request that names no model runs on the provider's current default,
 *       chosen by the catalogue from what the provider lists and what answered a
 *       test call;</li>
 *   <li>a request naming a retired or unusable model runs on its replacement;</li>
 *   <li>a model reported gone by a live request is set aside at once, while the
 *       provider's list is checked to confirm it.</li>
 * </ul>
 *
 * <p>Held as an in-memory snapshot, refreshed after every check and every few
 * minutes, so a request never waits on the database for this.
 */
@Component
public class ModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelResolver.class);

    private final ModelRepository models;
    private final ModelCatalogStore store;
    private final LlmProperties props;
    private final Map<String, ProviderCatalogClient> clients = new HashMap<>();
    private final ModelCatalogSettings settings;
    private final Clock clock;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final Map<String, Instant> quarantined = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ModelResolver(ModelRepository models, ModelCatalogStore store, LlmProperties props,
                         List<ProviderCatalogClient> clients, ModelCatalogSettings settings) {
        this(models, store, props, clients, settings, Clock.systemUTC());
    }

    ModelResolver(ModelRepository models, ModelCatalogStore store, LlmProperties props,
                  List<ProviderCatalogClient> clients, ModelCatalogSettings settings, Clock clock) {
        this.models = models;
        this.store = store;
        this.props = props;
        for (ProviderCatalogClient c : clients) {
            this.clients.put(c.provider(), c);
        }
        this.settings = settings;
        this.clock = clock;
    }

    /** The model to use when a request to {@code provider} names none. Null only for an unknown provider. */
    public String defaultFor(String provider) {
        String d = snapshot.defaults.get(provider);
        if (d != null && !isQuarantined(provider, d)) {
            return d;
        }
        // Nothing usable in the catalogue (fresh install, provider down, or the
        // default was just set aside): the next best thing the snapshot knows,
        // then the configured model, then the seed.
        for (String alt : snapshot.usable.getOrDefault(provider, List.of())) {
            if (!isQuarantined(provider, alt)) {
                return alt;
            }
        }
        String configured = configured(provider);
        if (configured != null) {
            return configured;
        }
        ProviderCatalogClient c = clients.get(provider);
        return c == null || c.seeds().isEmpty() ? null : c.seeds().get(0).id();
    }

    /**
     * The model a request to {@code provider} naming {@code requested} runs on.
     * Unknown names pass through unchanged: they may be newer than the last
     * check, and the provider is the one to say no.
     */
    public String resolve(String provider, String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultFor(provider);
        }
        String redirect = snapshot.redirects.get(key(provider, requested));
        if (redirect != null && !isQuarantined(provider, redirect)) {
            return redirect;
        }
        if (isQuarantined(provider, requested) || redirect != null) {
            return defaultFor(provider);
        }
        // A model of another provider (a request built for Gemini that failed
        // over to Groq) means nothing to this one: use its own default.
        String owner = snapshot.ownerOf.get(requested);
        if (owner != null && !owner.equals(provider)) {
            return defaultFor(provider);
        }
        return requested;
    }

    /** The catalogue already sends requests naming this model elsewhere (it is retired or unusable). */
    public boolean isRedirected(String provider, String model) {
        return snapshot.redirects.containsKey(key(provider, model));
    }

    public boolean isQuarantined(String provider, String model) {
        Instant at = quarantined.get(key(provider, model));
        if (at == null) {
            return false;
        }
        if (at.plus(settings.quarantine()).isBefore(clock.instant())) {
            quarantined.remove(key(provider, model));
            return false;
        }
        return true;
    }

    /** Set a model aside until the provider's list confirms or clears the report. */
    public void quarantine(String provider, String model) {
        quarantined.put(key(provider, model), clock.instant());
    }

    public void release(String provider, String model) {
        quarantined.remove(key(provider, model));
    }

    /** Reloads defaults and redirects from the catalogue. A failure keeps the last good snapshot. */
    @Scheduled(fixedDelayString = "${continuum.models.resolver-refresh-ms:300000}", initialDelay = 30_000)
    public void refresh() {
        try {
            List<ModelEntity> all = models.findAll();
            Map<String, ModelCatalogStore.ProviderState> states = new HashMap<>();
            for (ModelCatalogStore.ProviderState s : store.states()) {
                states.put(s.provider, s);
            }
            Map<String, String> defaults = new HashMap<>();
            Map<String, List<String>> usable = new HashMap<>();
            Map<String, String> redirects = new HashMap<>();
            Map<String, String> ownerOf = new HashMap<>();
            Map<String, List<DefaultModelPicker.Candidate>> candidates = new HashMap<>();
            for (ModelEntity m : all) {
                ownerOf.putIfAbsent(m.getModelName(), m.getProvider());
                if (m.getStatus() == ModelStatus.ACTIVE && m.isChat()) {
                    candidates.computeIfAbsent(m.getProvider(), k -> new ArrayList<>()).add(candidate(m));
                }
            }
            for (var e : candidates.entrySet()) {
                String provider = e.getKey();
                ModelCatalogStore.ProviderState st = states.get(provider);
                DefaultModelPicker.pick(e.getValue(), st == null ? null : st.pinnedModel, configured(provider),
                                st == null ? null : st.defaultModel, null)
                        .ifPresent(c -> defaults.put(provider, c.model()));
                List<DefaultModelPicker.Candidate> sorted = new ArrayList<>(e.getValue());
                String d = defaults.get(provider);
                List<String> order = new ArrayList<>();
                if (d != null) {
                    order.add(d);
                    DefaultModelPicker.replacementFor(d, sorted, null).ifPresent(order::add);
                }
                sorted.stream().map(DefaultModelPicker.Candidate::id).filter(id -> !order.contains(id)).forEach(order::add);
                usable.put(provider, order);
            }
            for (ModelEntity m : all) {
                boolean unusable = m.getStatus() == ModelStatus.REMOVED || m.getStatus() == ModelStatus.UNAVAILABLE
                        || m.getStatus() == ModelStatus.DEPRECATED;
                if (!unusable) {
                    continue;
                }
                String to = m.getReplacedBy();
                if (to == null) {
                    to = DefaultModelPicker.replacementFor(m.getModelName(),
                            candidates.getOrDefault(m.getProvider(), List.of()), defaults.get(m.getProvider())).orElse(null);
                }
                if (to != null) {
                    redirects.put(key(m.getProvider(), m.getModelName()), to);
                }
            }
            snapshot = new Snapshot(Map.copyOf(defaults), Map.copyOf(usable), Map.copyOf(redirects), Map.copyOf(ownerOf));
        } catch (RuntimeException e) {
            log.warn("Model resolver refresh failed; keeping the previous snapshot: {}", e.getMessage());
        }
    }

    static DefaultModelPicker.Candidate candidate(ModelEntity m) {
        return new DefaultModelPicker.Candidate(m.getModelName(), m.isPreview(), m.getContextWindow(),
                m.getProviderCreatedAt());
    }

    /** The model named in configuration (GROQ_MODEL, GEMINI_MODEL), if any: a preference, not a requirement. */
    public String configured(String provider) {
        LlmProperties.Provider p = switch (provider) {
            case "groq" -> props.getGroq();
            case "gemini" -> props.getGemini();
            default -> null;
        };
        return p == null || p.getModel() == null || p.getModel().isBlank() ? null : p.getModel().trim();
    }

    private static String key(String provider, String model) {
        return provider + "\u0000" + model;
    }

    private record Snapshot(Map<String, String> defaults, Map<String, List<String>> usable,
                            Map<String, String> redirects, Map<String, String> ownerOf) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
    }
}
