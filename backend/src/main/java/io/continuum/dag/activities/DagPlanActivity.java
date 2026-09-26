package io.continuum.dag.activities;

import com.fasterxml.jackson.databind.JsonNode;
import io.continuum.common.Json;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.Plan;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V6 Step 1 — the compiler. A lightweight LLM planner converts the incoming
 * request into a typed verification plan (claims + dependencies + checks).
 * If the LLM output cannot be parsed as a strict plan, a deterministic
 * fallback decomposition is used so the DAG always has a valid plan.
 */
@Component
public class DagPlanActivity implements Activity {

    public static final String TYPE = "dag.plan";
    static final List<String> DEFAULT_CHECKS =
            List.of("LOGIC_CONSISTENCY", "EVIDENCE_GROUNDING", "CONSTRAINT_CHECK");
    static final Set<String> KNOWN_CHECKS =
            Set.of("LOGIC_CONSISTENCY", "EVIDENCE_GROUNDING", "CONSTRAINT_CHECK", "SCHEMA_ALIGNMENT");

    private static final Logger log = LoggerFactory.getLogger(DagPlanActivity.class);
    private static final int MAX_CLAIMS = 4;

    private final ProviderRouter router;
    private final Json json;

    public DagPlanActivity(ProviderRouter router, Json json) {
        this.router = router;
        this.json = json;
    }

    public record Input(String prompt) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        Input in = ctx.input(inputJson, Input.class);
        try {
            LlmResponse resp = router.complete(LlmRequest.of(List.of(
                    Message.system("You decompose tasks into verifiable claims. Reply ONLY with JSON: "
                            + "{\"claims\":[{\"id\":1,\"statement\":\"...\",\"dependsOn\":[],"
                            + "\"checks\":[\"LOGIC_CONSISTENCY\",\"SCHEMA_ALIGNMENT\","
                            + "\"EVIDENCE_GROUNDING\",\"CONSTRAINT_CHECK\"]}]} — at most "
                            + MAX_CLAIMS + " atomic claims."),
                    Message.user(in.prompt()))));
            Plan parsed = parse(in.prompt(), resp.content());
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception e) {
            log.warn("dag.plan LLM decomposition unavailable, using deterministic fallback: {}",
                    e.getMessage());
        }
        return fallbackPlan(in.prompt());
    }

    Plan parse(String task, String content) {
        try {
            String cleaned = content.replaceAll("(?s)```(json)?", "").trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode root = json.mapper().readTree(cleaned.substring(start, end + 1));
            JsonNode claims = root.get("claims");
            if (claims == null || !claims.isArray() || claims.isEmpty()) {
                return null;
            }
            // Claims are renumbered 1..n (a model's own ids can repeat or skip),
            // so its dependsOn references are translated through the same map.
            // Before, a skipped blank claim shifted every id after it and the
            // dependencies pointed at the wrong claims.
            List<JsonNode> kept = new ArrayList<>();
            Map<Integer, Integer> renumber = new HashMap<>();
            for (JsonNode c : claims) {
                if (kept.size() >= MAX_CLAIMS) {
                    break;
                }
                if (c.path("statement").asText("").isBlank()) {
                    continue;
                }
                kept.add(c);
                if (c.has("id")) {
                    renumber.putIfAbsent(c.path("id").asInt(), kept.size());
                }
            }
            List<Claim> out = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                JsonNode c = kept.get(i);
                int id = i + 1;
                Set<Integer> deps = new LinkedHashSet<>();
                c.path("dependsOn").forEach(d -> {
                    Integer to = renumber.get(d.asInt());
                    if (to != null && to < id) {
                        deps.add(to); // only earlier claims: no self-loops, no cycles
                    }
                });
                // Each check once, and only checks a verifier exists for; a
                // repeated check made two trace nodes with one key.
                Set<String> checks = new LinkedHashSet<>();
                c.path("checks").forEach(ch -> {
                    String name = ch.asText("").trim().toUpperCase(java.util.Locale.ROOT);
                    if (KNOWN_CHECKS.contains(name)) {
                        checks.add(name);
                    }
                });
                out.add(new Claim(id, c.path("statement").asText().trim(), List.copyOf(deps),
                        checks.isEmpty() ? DEFAULT_CHECKS : List.copyOf(checks)));
            }
            return out.isEmpty() ? null : new Plan(task, out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Deterministic decomposition: sentence claims + structure-aware checks. */
    static Plan fallbackPlan(String prompt) {
        List<String> checks = new ArrayList<>(DEFAULT_CHECKS);
        String lower = prompt.toLowerCase();
        if (lower.contains("json") || lower.contains("sql") || lower.contains("schema")
                || lower.contains("config") || lower.contains("yaml")) {
            checks.add("SCHEMA_ALIGNMENT");
        }
        List<Claim> claims = new ArrayList<>();
        int id = 1;
        for (String s : prompt.split("(?<=[.!?])\\s+")) {
            if (s.isBlank() || claims.size() >= MAX_CLAIMS - 1) {
                continue;
            }
            claims.add(new Claim(id++, s.trim(), List.of(), checks));
        }
        if (claims.isEmpty()) {
            // id++, not 1: the closing claim below takes the next id, and a
            // prompt with no sentence in it used to give both claims id 1.
            claims.add(new Claim(id++, prompt.trim(), List.of(), checks));
        }
        // A closing synthesis claim depending on all others.
        List<Integer> all = claims.stream().map(Claim::id).toList();
        claims.add(new Claim(id, "The combined answer is complete and internally consistent.",
                all, List.of("LOGIC_CONSISTENCY")));
        return new Plan(prompt, claims);
    }
}
