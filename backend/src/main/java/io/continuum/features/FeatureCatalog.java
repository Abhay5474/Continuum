package io.continuum.features;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Every per-account feature, by what it does rather than the release it came
 * in. The switches used to be named after versions — {@code /v6/enable} turned
 * on the verification engine, {@code /v8/firewall/enable} the prompt firewall —
 * so knowing which switch did what meant knowing the project's history. Those
 * endpoints still work; this is the same set of switches under their real names.
 *
 * <p>A feature with a plain on/off here can be flipped from one place. One with
 * modes (the quality gate: monitor or enforce) shows its state and is set on
 * its own page. {@code labs} marks research features: complete and tested, but
 * not needed for the engine's core guarantees, and more likely to change.
 */
@Service
public class FeatureCatalog {

    /** One feature: its key and name, where it lives, and how to read and flip it. */
    public record Feature(String key, String name, String area, String route, boolean labs, String summary,
                          Predicate<String> isOn, BiConsumer<String, Boolean> setOn) {
    }

    private final List<Feature> features = new ArrayList<>();

    public FeatureCatalog(ObjectProvider<io.continuum.cache.SemanticCacheService> cache,
                          ObjectProvider<io.continuum.cascade.ResponseCascadeService> cascade,
                          ObjectProvider<io.continuum.dag.ConsensusDagService> dag,
                          ObjectProvider<io.continuum.mmu.ContextMMU> mmu,
                          ObjectProvider<io.continuum.firewall.PromptFirewallService> firewall,
                          ObjectProvider<io.continuum.compression.PromptCompressionService> compression,
                          ObjectProvider<io.continuum.context.PromptContextService> context,
                          ObjectProvider<io.continuum.godmode.GodModeService> godMode,
                          ObjectProvider<io.continuum.quality.QualityGateService> quality,
                          ObjectProvider<io.continuum.drift.SemanticBreakerService> breaker) {
        add("semantic-cache", "Semantic Cache", "Prompt", "/cache", false,
                "Answers an equivalent question from a previous answer, at no cost.",
                d -> cache.getObject().enabledFor(d), (d, on) -> cache.getObject().setEnabled(d, on));
        add("model-cascade", "Model Cascade", "Traffic", "/cascade", false,
                "Answers on a cheap model first and escalates only when a judge says so.",
                d -> cascade.getObject().enabledFor(d), (d, on) -> cascade.getObject().setEnabled(d, on));
        add("prompt-firewall", "Prompt Firewall", "Prompt", "/guard", false,
                "Redacts personal data going out and secrets coming back; screens injection attempts.",
                d -> firewall.getObject().enabledFor(d), (d, on) -> firewall.getObject().setEnabled(d, on));
        add("prompt-compression", "Prompt Compression", "Prompt", "/guard", false,
                "Shortens long prompts before they are sent, within a quality budget.",
                d -> compression.getObject().enabledFor(d), (d, on) -> compression.getObject().setEnabled(d, on));
        add("chat-context", "Context Transformers", "Prompt", "/context", false,
                "Turns a recognised payload in a chat message (a log, a spreadsheet, an email thread) into what a model reads best.",
                d -> context.getObject().enabledFor(d), (d, on) -> context.getObject().setEnabled(d, on));
        add("quality-gate", "Quality Gate", "Reliability", "/quality", false,
                "Scores each finished answer against its request; monitors or repairs. Set its mode on its page.",
                d -> quality.getObject().activeFor(d), null);
        add("semantic-breaker", "Semantic Breaker", "Reliability", "/breaker", false,
                "Takes a model out of rotation when its answers degrade, not only when it errors. Set on its page.",
                d -> breaker.getObject().enabledFor(d), null);
        add("verification-engine", "Verification Engine", "Reliability", "/dag", true,
                "Solves, verifies and scores every answer as a graph of agents on the durable engine.",
                d -> dag.getObject().enabledFor(d), (d, on) -> dag.getObject().setEnabled(d, on));
        add("context-optimizer", "Context Optimizer", "Prompt", "/mmu", true,
                "Pages long histories out of the context window and back in when the model asks.",
                d -> mmu.getObject().enabledFor(d), (d, on) -> mmu.getObject().setEnabled(d, on));
        add("adaptive-policy", "Adaptive Policy", "Intelligence", "/godmode", true,
                "Learns memory and routing policy from traffic, promoted only after replay proves it.",
                d -> Boolean.TRUE.equals(godMode.getObject().status(d).get("enabled")),
                (d, on) -> godMode.getObject().setEnabled(d, on));
    }

    private void add(String key, String name, String area, String route, boolean labs, String summary,
                     Predicate<String> isOn, BiConsumer<String, Boolean> setOn) {
        features.add(new Feature(key, name, area, route, labs, summary, isOn, setOn));
    }

    /** Every feature and whether it is on for {@code developerId}. */
    public List<Map<String, Object>> list(String developerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Feature f : features) {
            out.add(view(f, developerId));
        }
        return out;
    }

    /** Switches a feature on or off. Refuses one that is set on its own page. */
    public Map<String, Object> set(String developerId, String key, boolean on) {
        Feature f = features.stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new io.continuum.portal.RequestScope.NotFoundException("No feature '" + key + "'"));
        if (f.setOn() == null) {
            throw new IllegalArgumentException(f.name() + " has modes rather than an on/off switch; set it on its page ("
                    + f.route() + ").");
        }
        f.setOn().accept(developerId, on);
        return view(f, developerId);
    }

    private static Map<String, Object> view(Feature f, String developerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", f.key());
        m.put("name", f.name());
        m.put("area", f.area());
        m.put("route", f.route());
        m.put("labs", f.labs());
        m.put("summary", f.summary());
        m.put("switchable", f.setOn() != null);
        Boolean on;
        try {
            on = f.isOn().test(developerId);
        } catch (RuntimeException e) {
            on = null;
        }
        m.put("on", on);
        return m;
    }
}
