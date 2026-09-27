package io.continuum.advisor;

import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.QualityGateSettingEntity;
import io.continuum.persistence.entity.UncertaintySettingEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.provider.ProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads an account's whole configuration and says what is wrong with it.
 *
 * <p>Every feature validates its own inputs, but none of them can see the
 * others. A latency ceiling no model has ever met, a verification engine that
 * quietly bypasses the cache and the cascade the account also turned on, a
 * chaos experiment left armed: each is a setting that "works" and produces
 * output nobody expected. The advisor looks at the settings together, against
 * what the account's own traffic has measured, and says so in plain words with
 * the fix and the page it is on.
 *
 * <p>Read-only. Each check is independent and a failing check is skipped, so
 * one broken service cannot hide the other advice.
 */
@Service
public class ConfigAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdvisor.class);

    /** One piece of advice. {@code id} is stable, so the console can remember a dismissal. */
    public record Advice(String id, String severity, String area, String title, String message,
                         String fix, String route) {
    }

    private final ObjectProvider<ProviderRouter> router;
    private final ObjectProvider<io.continuum.vault.CredentialVaultService> vault;
    private final ObjectProvider<DeveloperAuthRepository> devAuth;
    private final ObjectProvider<GatewayRequestLogRepository> requests;
    private final ObjectProvider<io.continuum.dag.ConsensusDagService> dag;
    private final ObjectProvider<io.continuum.cache.SemanticCacheService> cache;
    private final ObjectProvider<io.continuum.cascade.ResponseCascadeService> cascade;
    private final ObjectProvider<io.continuum.quality.QualityGateService> quality;
    private final ObjectProvider<io.continuum.uncertainty.SemanticUncertaintyService> uncertainty;
    private final ObjectProvider<io.continuum.drift.SemanticBreakerService> breaker;
    private final ObjectProvider<io.continuum.admission.CostAdmissionService> costLimits;
    private final ObjectProvider<io.continuum.compression.PromptCompressionService> compression;
    private final ObjectProvider<io.continuum.mmu.ContextMMU> mmu;
    private final ObjectProvider<io.continuum.context.PromptContextService> context;
    private final ObjectProvider<io.continuum.firewall.PromptFirewallService> firewall;
    private final ObjectProvider<io.continuum.autopilot.AutopilotService> autopilot;
    private final ObjectProvider<io.continuum.chaos.ChaosMonkey> chaos;
    private final ObjectProvider<io.continuum.aichaos.AiChaosEngine> aiChaos;
    private final ObjectProvider<io.continuum.registry.ModelRegistryService> registry;

    public ConfigAdvisor(ObjectProvider<ProviderRouter> router,
                         ObjectProvider<io.continuum.vault.CredentialVaultService> vault,
                         ObjectProvider<DeveloperAuthRepository> devAuth,
                         ObjectProvider<GatewayRequestLogRepository> requests,
                         ObjectProvider<io.continuum.dag.ConsensusDagService> dag,
                         ObjectProvider<io.continuum.cache.SemanticCacheService> cache,
                         ObjectProvider<io.continuum.cascade.ResponseCascadeService> cascade,
                         ObjectProvider<io.continuum.quality.QualityGateService> quality,
                         ObjectProvider<io.continuum.uncertainty.SemanticUncertaintyService> uncertainty,
                         ObjectProvider<io.continuum.drift.SemanticBreakerService> breaker,
                         ObjectProvider<io.continuum.admission.CostAdmissionService> costLimits,
                         ObjectProvider<io.continuum.compression.PromptCompressionService> compression,
                         ObjectProvider<io.continuum.mmu.ContextMMU> mmu,
                         ObjectProvider<io.continuum.context.PromptContextService> context,
                         ObjectProvider<io.continuum.firewall.PromptFirewallService> firewall,
                         ObjectProvider<io.continuum.autopilot.AutopilotService> autopilot,
                         ObjectProvider<io.continuum.chaos.ChaosMonkey> chaos,
                         ObjectProvider<io.continuum.aichaos.AiChaosEngine> aiChaos,
                         ObjectProvider<io.continuum.registry.ModelRegistryService> registry) {
        this.router = router;
        this.vault = vault;
        this.devAuth = devAuth;
        this.requests = requests;
        this.dag = dag;
        this.cache = cache;
        this.cascade = cascade;
        this.quality = quality;
        this.uncertainty = uncertainty;
        this.breaker = breaker;
        this.costLimits = costLimits;
        this.compression = compression;
        this.mmu = mmu;
        this.context = context;
        this.firewall = firewall;
        this.autopilot = autopilot;
        this.chaos = chaos;
        this.aiChaos = aiChaos;
        this.registry = registry;
    }

    /** What the account's recent traffic measured; the yardstick for settings. */
    record Traffic(int answered, long fastestMs, long medianMs, long medianVerifiedMs, int medianTokens,
                   int largestTokens, double cheapestCost, int failed) {
        static Traffic of(List<GatewayRequestLogEntity> rows) {
            List<Long> plain = new ArrayList<>();
            List<Long> verified = new ArrayList<>();
            List<Integer> tokens = new ArrayList<>();
            double cheapest = Double.MAX_VALUE;
            int failed = 0;
            for (GatewayRequestLogEntity r : rows) {
                if (!r.isSuccess()) {
                    failed++;
                    continue;
                }
                String reason = r.getRoutingReason() == null ? "" : r.getRoutingReason();
                if (reason.startsWith("semantic-cache")) {
                    continue; // a cache hit says nothing about how fast a model is
                }
                (reason.contains("consensus DAG") ? verified : plain).add(r.getLatencyMs());
                if (r.getTokens() > 0) {
                    tokens.add(r.getTokens());
                }
                if (r.getCostUsd() > 0) {
                    cheapest = Math.min(cheapest, r.getCostUsd());
                }
            }
            plain.sort(Long::compare);
            verified.sort(Long::compare);
            tokens.sort(Integer::compare);
            return new Traffic(plain.size() + verified.size(),
                    plain.isEmpty() ? -1 : plain.get(0),
                    plain.isEmpty() ? -1 : plain.get(plain.size() / 2),
                    verified.isEmpty() ? -1 : verified.get(verified.size() / 2),
                    tokens.isEmpty() ? -1 : tokens.get(tokens.size() / 2),
                    tokens.isEmpty() ? -1 : tokens.get(tokens.size() - 1),
                    cheapest == Double.MAX_VALUE ? -1 : cheapest,
                    failed);
        }
    }

    public Map<String, Object> advise(String developerId) {
        List<Advice> out = new ArrayList<>();
        Traffic traffic = traffic(developerId);
        int checks = 0;
        checks += run("providers", out, o -> providers(developerId, o));
        checks += run("latency", out, o -> latency(developerId, traffic, o));
        checks += run("verification", out, o -> verification(developerId, traffic, o));
        checks += run("cache", out, o -> cache(developerId, o));
        checks += run("cascade", out, o -> cascade(developerId, o));
        checks += run("quality", out, o -> quality(developerId, traffic, o));
        checks += run("confidence", out, o -> confidence(developerId, o));
        checks += run("breaker", out, o -> breaker(developerId, o));
        checks += run("cost-limits", out, o -> costLimits(developerId, traffic, o));
        checks += run("chaos", out, o -> chaos(developerId, o));
        checks += run("failures", out, o -> failures(traffic, o));
        out.sort((a, b) -> Integer.compare(rank(a.severity()), rank(b.severity())));
        return Map.of("advice", out, "checks", checks, "checkedAt", Instant.now().toString(),
                "measuredRequests", traffic.answered());
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "error" -> 0;
            case "warn" -> 1;
            default -> 2;
        };
    }

    private int run(String name, List<Advice> out, Consumer<List<Advice>> check) {
        try {
            check.accept(out);
            return 1;
        } catch (RuntimeException e) {
            log.debug("Advisor check '{}' skipped: {}", name, e.getMessage());
            return 0;
        }
    }

    private Traffic traffic(String developerId) {
        try {
            return Traffic.of(requests.getObject()
                    .findByDeveloperIdOrderByCreatedAtDesc(developerId, PageRequest.of(0, 200)).getContent());
        } catch (RuntimeException e) {
            return Traffic.of(List.of());
        }
    }

    // ------------------------------------------------------------------ checks

    private void providers(String dev, List<Advice> out) {
        boolean platform = !router.getObject().availableChain().isEmpty();
        boolean own = !vault.getObject().listProviders(dev).isEmpty();
        boolean useOwn = devAuth.getObject().findById(dev)
                .map(io.continuum.persistence.entity.DeveloperAuthEntity::isUseOwnKeysPrimary).orElse(true);
        if (!platform && !own) {
            out.add(new Advice("no-provider", "error", "Providers", "No model provider is set up",
                    "Every chat request will fail with 503 no_provider: this server has no Groq or Gemini key, and neither does your account.",
                    "Add your own Groq or Gemini key under API Keys & Providers (both have free tiers).", "/portal"));
        } else if (!platform && !useOwn) {
            out.add(new Advice("own-keys-off", "error", "Providers", "Your keys are switched off",
                    "\"Use my provider keys\" is off, so requests run on the server's keys only, and this server has none. Every request will fail.",
                    "Turn \"Use my provider keys\" back on under API Keys & Providers.", "/portal"));
        }
        if (registry.getObject().active().isEmpty() && (platform || own)) {
            out.add(new Advice("no-models", "error", "Models", "No model is available to route to",
                    "Keys are set up, but the model catalogue has no usable model, so requests fail before any provider is called.",
                    "Run a check on the Models page to refresh the catalogue.", "/models"));
        }
    }

    private void latency(String dev, Traffic t, List<Advice> out) {
        var svc = autopilot.getObject();
        var cfg = svc.getOrCreateConfig(dev);
        var profile = svc.profileOf(cfg);
        long max = profile.maxLatencyMs();
        if (max <= 0) {
            return;
        }
        if (t.fastestMs() > 0 && max < t.fastestMs()) {
            out.add(new Advice("latency-impossible", "error", "Optimization",
                    "Max latency is lower than any model has answered",
                    String.format("Your profile allows %,d ms, but the fastest answer your account has ever had took %,d ms "
                            + "(median %,d ms). No model can meet this, so Autopilot will keep tuning toward a target it cannot reach.",
                            max, t.fastestMs(), t.medianMs()),
                    String.format("Raise max latency to at least %,d ms; %,d ms leaves room for normal variation.",
                            t.medianMs(), Math.round(t.medianMs() * 1.5 / 100.0) * 100),
                    "/autopilot"));
        } else if (max < 300) {
            out.add(new Advice("latency-too-low", "warn", "Optimization", "Max latency is below what any hosted model does",
                    String.format("%,d ms is less than a network round trip plus the shortest model answer. Hosted models "
                            + "typically take 300 ms to a few seconds.", max),
                    "Raise max latency to at least 1,000 ms, then adjust once you have traffic to measure.", "/autopilot"));
        } else if (t.medianMs() > 0 && max < t.medianMs()) {
            out.add(new Advice("latency-tight", "warn", "Optimization", "Most answers are slower than your max latency",
                    String.format("Max latency is %,d ms but the median answer takes %,d ms, so most requests miss it.",
                            max, t.medianMs()),
                    String.format("Raise it to about %,d ms, or choose Low latency mode to favour faster models.", t.medianMs()),
                    "/autopilot"));
        }
        if (profile.maxCostPerRequest() > 0 && t.cheapestCost() > 0 && profile.maxCostPerRequest() < t.cheapestCost()) {
            out.add(new Advice("cost-impossible", "warn", "Optimization", "Max cost is lower than any answer has cost",
                    String.format("Your profile allows $%.6f per request; the cheapest answer so far cost $%.6f.",
                            profile.maxCostPerRequest(), t.cheapestCost()),
                    "Raise max cost per request, or use shorter prompts and a smaller max_tokens.", "/autopilot"));
        }
        List<String> allowed = profile.allowedProviders();
        // Only a policy that is running can be starved of providers.
        if (cfg.isEnabled() && allowed != null && !allowed.isEmpty()) {
            List<String> configured = new ArrayList<>(router.getObject().availableChain());
            vault.getObject().listProviders(dev).forEach(c -> configured.add(c.provider()));
            if (allowed.stream().noneMatch(configured::contains)) {
                out.add(new Advice("allowed-none", "warn", "Optimization", "None of your allowed providers is set up",
                        "Autopilot may only use " + String.join(", ", allowed) + ", but none of them has a key here, so its policy cannot apply.",
                        "Add a key for one of them, or allow a provider that is set up.", "/autopilot"));
            }
        }
    }

    private void verification(String dev, Traffic t, List<Advice> out) {
        if (!dag.getObject().enabledFor(dev)) {
            return;
        }
        List<String> bypassed = new ArrayList<>();
        if (cache.getObject().enabledFor(dev)) {
            bypassed.add("Semantic Cache");
        }
        if (cascade.getObject().enabledFor(dev)) {
            bypassed.add("Model Cascade");
        }
        if (quality.getObject().activeFor(dev)) {
            bypassed.add("Quality Gate");
        }
        UncertaintySettingEntity u = uncertainty.getObject().settingsFor(dev);
        if (u.getMode() != UncertaintySettingEntity.Mode.OFF) {
            bypassed.add("Confidence");
        }
        if (compression.getObject().enabledFor(dev)) {
            bypassed.add("Prompt Compression");
        }
        if (mmu.getObject().enabledFor(dev)) {
            bypassed.add("Context Optimizer");
        }
        if (context.getObject().enabledFor(dev)) {
            bypassed.add("Context Transformers");
        }
        if (!bypassed.isEmpty()) {
            out.add(new Advice("dag-bypasses", "warn", "Verification Engine",
                    "The Verification Engine skips " + String.join(", ", bypassed),
                    "With verification on, every request is answered by the verification graph instead of the normal path, "
                            + "so these features are on but do nothing to chat requests. (Prompt Firewall still applies.)",
                    "Keep verification for the requests that need it and turn it off otherwise, or turn the skipped features off to avoid confusion.",
                    "/dag"));
        }
        var cfg = autopilot.getObject().getOrCreateConfig(dev);
        long max = autopilot.getObject().profileOf(cfg).maxLatencyMs();
        boolean lowLatency = "LOW_LATENCY".equalsIgnoreCase(cfg.getMode());
        long typical = t.medianVerifiedMs() > 0 ? t.medianVerifiedMs() : 3000;
        if (lowLatency || (max > 0 && max < typical)) {
            out.add(new Advice("dag-latency", "warn", "Verification Engine", "Verification is slower than your latency target",
                    String.format("A verified answer runs several models (typically %,d ms for your account)%s. That cannot meet %s.",
                            typical, t.medianVerifiedMs() > 0 ? "" : ", estimated",
                            lowLatency ? "Low latency mode" : String.format("a %,d ms max latency", max)),
                    "Turn verification off for latency-sensitive traffic, or relax the latency target.", "/dag"));
        }
        if (!firewall.getObject().enabledFor(dev)) {
            out.add(new Advice("dag-no-firewall", "info", "Verification Engine", "Verification sends each prompt to several models",
                    "Every verified request reaches several providers. With the Prompt Firewall off, any personal data in a prompt reaches all of them.",
                    "Turn on the Prompt Firewall to redact personal data first.", "/guard"));
        }
    }

    private void cache(String dev, List<Advice> out) {
        var svc = cache.getObject();
        if (!svc.enabledFor(dev)) {
            return;
        }
        Object th = svc.status(dev).get("similarityThreshold");
        double threshold = th instanceof Number n ? n.doubleValue() : 0.92;
        if (threshold < 0.85) {
            out.add(new Advice("cache-loose", "warn", "Semantic Cache", "The cache matches loosely",
                    String.format("A similarity of %.2f treats questions with different meanings as the same, so callers get answers to questions they did not ask.", threshold),
                    "Use 0.90 or higher.", "/cache"));
        }
        if (aiChaos.getObject().isActive(dev)) {
            out.add(new Advice("cache-chaos", "info", "Semantic Cache", "Nothing is cached while AI chaos is armed",
                    "Answers corrupted on purpose by AI chaos are not stored, so the cache will not fill until the experiment is reset.",
                    "Reset AI chaos when the experiment is over.", "/ai-chaos"));
        }
    }

    private void cascade(String dev, List<Advice> out) {
        var svc = cascade.getObject();
        if (!svc.enabledFor(dev)) {
            return;
        }
        double threshold = svc.thresholdFor(dev);
        if (threshold >= 0.95) {
            out.add(new Advice("cascade-escalates-all", "warn", "Model Cascade", "The cascade escalates almost everything",
                    String.format("A confidence bar of %.2f is rarely met, so nearly every request pays for both the cheap and the strong model: more cost and latency than no cascade at all.", threshold),
                    "Use a bar between 0.6 and 0.85.", "/cascade"));
        } else if (threshold <= 0.3) {
            out.add(new Advice("cascade-accepts-all", "warn", "Model Cascade", "The cascade accepts almost everything",
                    String.format("A confidence bar of %.2f accepts weak cheap answers, so the strong model is almost never used.", threshold),
                    "Use a bar between 0.6 and 0.85.", "/cascade"));
        }
    }

    private void quality(String dev, Traffic t, List<Advice> out) {
        QualityGateSettingEntity cfg = quality.getObject().settingsFor(dev);
        if (cfg.getMode() == QualityGateSettingEntity.Mode.OFF) {
            return;
        }
        if (cfg.getMode() == QualityGateSettingEntity.Mode.ENFORCE) {
            long typical = t.medianMs() > 0 ? t.medianMs() : 1500;
            if (cfg.getBudgetMs() < typical) {
                out.add(new Advice("quality-budget", "warn", "Quality Gate", "Repairs cannot finish inside their budget",
                        String.format("A repair is one more model call; your answers take about %,d ms, but the repair budget is %,d ms, so repairs are discarded as over budget and you pay for them anyway.",
                                typical, cfg.getBudgetMs()),
                        String.format("Raise the repair budget to at least %,d ms.", Math.max(2000, typical * 2)), "/quality"));
            }
            if (cfg.getMaxRepairs() <= 0) {
                out.add(new Advice("quality-no-repairs", "warn", "Quality Gate", "Enforce mode with zero repairs",
                        "The gate is enforcing but may not repair anything, so it behaves like Monitor.",
                        "Allow at least one repair, or switch to Monitor.", "/quality"));
            }
        }
        if (cfg.getThreshold() >= 0.9) {
            out.add(new Advice("quality-strict", "info", "Quality Gate", "The quality bar is very strict",
                    String.format("A bar of %.2f flags most good answers too.", cfg.getThreshold()),
                    "Try 0.6 to 0.8.", "/quality"));
        }
    }

    private void confidence(String dev, List<Advice> out) {
        UncertaintySettingEntity u = uncertainty.getObject().settingsFor(dev);
        if (u.getMode() == UncertaintySettingEntity.Mode.ALWAYS && u.getSamples() >= 4) {
            out.add(new Advice("confidence-cost", "warn", "Confidence", "Every request is answered " + u.getSamples() + " times",
                    "Always mode with " + u.getSamples() + " samples multiplies the cost and time of every request by about " + u.getSamples() + ".",
                    "Use Adaptive mode, which samples only when the answer looks uncertain, or 2 to 3 samples.", "/confidence"));
        }
        if (u.getMode() != UncertaintySettingEntity.Mode.OFF && u.getTemperature() <= 0.05) {
            out.add(new Advice("confidence-temperature", "warn", "Confidence", "Resampling at temperature 0 measures nothing",
                    "At temperature 0 every sample is the same answer, so confidence always reads 100%.",
                    "Use a temperature of 0.5 to 0.8 for the samples.", "/confidence"));
        }
    }

    private void breaker(String dev, List<Advice> out) {
        var svc = breaker.getObject();
        if (!svc.enabledFor(dev)) {
            return;
        }
        // The breaker scores answers itself; its only failure mode is having
        // nowhere to divert to.
        if (registry.getObject().active().size() < 2) {
            out.add(new Advice("breaker-one-model", "info", "Semantic Breaker", "Only one model to divert between",
                    "The breaker takes a degrading model out of rotation, but with one usable model there is nowhere to send traffic, so it keeps serving.",
                    "Add a second provider key so the breaker has an alternative.", "/portal"));
        }
    }

    private void costLimits(String dev, Traffic t, List<Advice> out) {
        var svc = costLimits.getObject();
        if (!svc.enabled(dev)) {
            return;
        }
        var cfg = svc.settingsFor(dev);
        if (cfg.getRequestsPerMin() < 1 || cfg.getTokensPerMin() < 1) {
            out.add(new Advice("cost-zero", "error", "Cost limits", "Cost limits admit nothing",
                    "A limit of zero refuses every request.", "Set requests and tokens per minute above zero.", "/cost-limits"));
        } else if (t.largestTokens() > 0 && cfg.getTokensPerMin() < t.largestTokens()) {
            out.add(new Advice("cost-too-small", "warn", "Cost limits", "Your larger requests can never be admitted",
                    String.format("The token allowance is %,d per minute, but some of your requests use %,d tokens, so they are refused however long they wait.",
                            cfg.getTokensPerMin(), t.largestTokens()),
                    String.format("Raise tokens per minute to at least %,d.", t.largestTokens() * 3), "/cost-limits"));
        }
    }

    private void chaos(String dev, List<Advice> out) {
        var s = chaos.getObject().state(dev);
        if (s.primaryProviderDown() || s.providerFailureRate() > 0) {
            out.add(new Advice("chaos-provider", "warn", "Chaos Lab", "Chaos Lab is failing your provider calls",
                    s.primaryProviderDown() ? "The primary provider is switched off on purpose, so every request fails over."
                            : String.format("%.0f%% of provider calls fail on purpose.", s.providerFailureRate() * 100),
                    "Reset Chaos Lab when the experiment is over.", "/chaos"));
        }
        if (s.activityFailureRate() > 0 || s.activityLatencyMs() > 0 || s.sinkFailureRate() > 0) {
            out.add(new Advice("chaos-workflows", "info", "Chaos Lab", "Chaos Lab is slowing or failing workflow steps",
                    "Workflow runs will retry and take longer while this is armed.", "Reset Chaos Lab when the experiment is over.", "/chaos"));
        }
        if (aiChaos.getObject().isActive(dev)) {
            out.add(new Advice("chaos-ai", "warn", "Chaos Lab", "AI chaos is corrupting answers",
                    "Some answers your apps receive are deliberately wrong while AI chaos is armed.",
                    "Reset AI chaos when the experiment is over.", "/ai-chaos"));
        }
    }

    private void failures(Traffic t, List<Advice> out) {
        int total = t.answered() + t.failed();
        if (total >= 5 && t.failed() * 2 > total) {
            out.add(new Advice("failing", "error", "Gateway", "Most recent requests failed",
                    String.format("%d of your last %d requests got an error instead of an answer.", t.failed(), total),
                    "Open the Gateway request feed to see why; the advice above may already name the cause.", "/gateway"));
        }
    }
}
