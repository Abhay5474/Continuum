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
import java.util.List;

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

    private Plan parse(String task, String content) {
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
            List<Claim> out = new ArrayList<>();
            int id = 1;
            for (JsonNode c : claims) {
                if (out.size() >= MAX_CLAIMS) {
                    break;
                }
                String statement = c.path("statement").asText("");
                if (statement.isBlank()) {
                    continue;
                }
                List<Integer> deps = new ArrayList<>();
                c.path("dependsOn").forEach(d -> deps.add(d.asInt()));
                List<String> checks = new ArrayList<>();
                c.path("checks").forEach(ch -> checks.add(ch.asText()));
                out.add(new Claim(id++, statement, deps,
                        checks.isEmpty() ? DEFAULT_CHECKS : checks));
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
            claims.add(new Claim(1, prompt.trim(), List.of(), checks));
        }
        // A closing synthesis claim depending on all others.
        List<Integer> all = claims.stream().map(Claim::id).toList();
        claims.add(new Claim(id, "The combined answer is complete and internally consistent.",
                all, List.of("LOGIC_CONSISTENCY")));
        return new Plan(prompt, claims);
    }
}
