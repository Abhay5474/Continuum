package io.continuum.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.ModelRepository;
import io.continuum.portal.RequestScope;
import io.continuum.registry.ModelStatus;
import io.continuum.registry.catalog.ModelCatalogService;
import io.continuum.registry.catalog.ModelCatalogStore;
import io.continuum.registry.catalog.ModelResolver;
import io.continuum.registry.catalog.ProbeResult;
import io.continuum.registry.catalog.ProviderCatalogClient;
import io.continuum.registry.catalog.ProviderPolicies;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model catalogue: every model each provider lists, what was verified,
 * what was retired and what replaced it — and the "Check now" button.
 *
 * <p>Reads are open to any signed-in user. A check is too, because it is cheap
 * and bounded (one list call per provider, a capped number of test calls) and
 * gated (one at a time, with a cooldown). Pinning a default changes what every
 * tenant runs on, so it is the operator's.
 */
@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

    private final ModelCatalogService catalogue;
    private final ModelCatalogStore store;
    private final ModelRepository models;
    private final ModelResolver resolver;
    private final ObjectMapper mapper;

    public ModelCatalogController(ModelCatalogService catalogue, ModelCatalogStore store, ModelRepository models,
                                  ModelResolver resolver, ObjectMapper mapper) {
        this.catalogue = catalogue;
        this.store = store;
        this.models = models;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, ModelCatalogStore.ProviderState> states = new HashMap<>();
        for (ModelCatalogStore.ProviderState s : store.states()) {
            states.put(s.provider, s);
        }
        Map<String, Boolean> freeTier = new HashMap<>();
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ProviderCatalogClient c : catalogue.clients()) {
            ModelCatalogStore.ProviderState st = states.getOrDefault(c.provider(), new ModelCatalogStore.ProviderState(c.provider()));
            freeTier.put(c.provider(), c.freeTier());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("provider", c.provider());
            p.put("label", ModelCatalogService.label(c.provider()));
            p.put("configured", c.configured());
            p.put("freeTier", c.freeTier());
            p.put("lastListOkAt", st.lastListOkAt);
            p.put("lastAttemptAt", st.lastAttemptAt);
            p.put("lastError", st.lastError);
            p.put("listedCount", st.listedCount);
            p.put("defaultModel", resolver.defaultFor(c.provider()));
            p.put("pinnedModel", st.pinnedModel);
            p.put("configuredModel", resolver.configured(c.provider()));
            p.put("nextCheckAt", c.configured() ? catalogue.nextDue(st) : null);
            providers.add(p);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ModelEntity m : models.findAll()) {
            rows.add(view(m, freeTier.getOrDefault(m.getProvider(), false)));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("provider")))
                .thenComparing(r -> rank(String.valueOf(r.get("status"))))
                .thenComparing(r -> String.valueOf(r.get("name"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providers);
        out.put("models", rows);
        out.put("policies", ProviderPolicies.all());
        out.put("check", checkState());
        return out;
    }

    /** Cheap enough to poll while a check runs. */
    @GetMapping("/check")
    public Map<String, Object> checkState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", catalogue.running().orElse(null));
        out.put("lastRun", store.lastRun().orElse(null));
        out.put("nextManualAt", catalogue.nextManualAt());
        return out;
    }

    /** "Check now". 202 when started; 409 while one runs; 429 during the cooldown. */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(HttpServletRequest req) {
        String by = RequestScope.developerId(req);
        ModelCatalogService.Start s = catalogue.requestCheck(by != null ? by : RequestScope.isOperator(req) ? "operator" : null);
        HttpStatus status = switch (s.outcome()) {
            case STARTED -> HttpStatus.ACCEPTED;
            case BUSY -> HttpStatus.CONFLICT;
            case COOLING_DOWN -> HttpStatus.TOO_MANY_REQUESTS;
            case NOTHING_TO_CHECK -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", s.outcome());
        body.put("message", s.message());
        body.put("startedAt", s.startedAt());
        body.put("retryAfterSeconds", s.retryAfterSeconds());
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (s.retryAfterSeconds() > 0) {
            b.header("Retry-After", String.valueOf(s.retryAfterSeconds()));
        }
        return b.body(body);
    }

    @GetMapping("/events")
    public List<ModelCatalogStore.Event> events(@RequestParam(defaultValue = "60") int limit) {
        return store.events(Math.max(1, Math.min(300, limit)));
    }

    @GetMapping("/runs")
    public List<ModelCatalogStore.Run> runs(@RequestParam(defaultValue = "10") int limit) {
        return store.recentRuns(Math.max(1, Math.min(50, limit)));
    }

    /** Pin a provider's default, or clear the pin with {@code model: null}. Operator only. */
    @PostMapping("/pin")
    public Map<String, Object> pin(@RequestBody PinRequest body, HttpServletRequest req) {
        if (!RequestScope.isOperator(req)) {
            throw new RequestScope.ForbiddenException();
        }
        String model = body.model() == null || body.model().isBlank() ? null : body.model().trim();
        catalogue.pin(body.provider(), model, RequestScope.developerId(req));
        return Map.of("provider", body.provider(), "defaultModel", String.valueOf(resolver.defaultFor(body.provider())));
    }

    public record PinRequest(String provider, String model) {
    }

    private Map<String, Object> view(ModelEntity m, boolean freeTierKey) {
        Map<String, Object> v = new LinkedHashMap<>();
        boolean quarantined = resolver.isQuarantined(m.getProvider(), m.getModelName());
        boolean routable = m.getStatus() == ModelStatus.ACTIVE && m.isChat() && !quarantined;
        v.put("id", m.getId());
        v.put("provider", m.getProvider());
        v.put("name", m.getModelName());
        v.put("displayName", m.getDisplayName());
        v.put("description", m.getDescription());
        v.put("kind", m.getKind() == null ? "CHAT" : m.getKind());
        v.put("status", m.getStatus());
        v.put("statusReason", m.getStatusReason());
        v.put("note", m.getNote());
        v.put("routable", routable);
        v.put("quarantined", quarantined);
        v.put("preview", m.isPreview());
        v.put("contextWindow", m.getContextWindow());
        v.put("maxOutputTokens", m.getMaxOutputTokens());
        v.put("providerCreatedAt", m.getProviderCreatedAt() > 0 ? Instant.ofEpochSecond(m.getProviderCreatedAt()) : null);
        v.put("verifiedAt", m.getVerifiedAt());
        v.put("probedAt", m.getProbedAt());
        v.put("probeOutcome", m.getProbeOutcome());
        v.put("limits", limits(m.getLimitsJson()));
        v.put("missingSince", m.getMissingSince());
        v.put("retiredAt", m.getRetiredAt());
        v.put("replacedBy", m.getReplacedBy());
        v.put("firstSeenAt", m.getFirstSeenAt());
        v.put("source", m.getSource());
        // "Free" is a verified fact or nothing: answered a test call on a key
        // declared free-tier, or refused with zero free quota.
        String free;
        if (ProbeResult.Outcome.NOT_FREE.name().equals(m.getProbeOutcome())) {
            free = "NO";
        } else if (m.getVerifiedAt() != null && m.getStatus() == ModelStatus.ACTIVE && freeTierKey) {
            free = "VERIFIED";
        } else {
            free = "UNKNOWN";
        }
        v.put("free", "mock".equals(m.getProvider()) ? "BUILT_IN" : free);
        return v;
    }

    private Map<String, Object> limits(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private static int rank(String status) {
        return switch (status) {
            case "ACTIVE" -> 0;
            case "DISCOVERED", "TESTING" -> 1;
            case "UNAVAILABLE" -> 2;
            case "DEPRECATED" -> 3;
            default -> 4;
        };
    }
}
