package io.continuum.dag.activities;

import io.continuum.common.Json;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.dag.DagModels.VerifierOutput;
import io.continuum.semantic.DecisionPolarity;
import io.continuum.semantic.TextVectors;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V6 Step 2 — a verifier node: one specialized, DETERMINISTIC check of a
 * solver's output. No LLM: verifiers reuse the V2 semantic machinery
 * ({@link DecisionPolarity}, {@link TextVectors}) and structural analysis, so
 * the same inputs always produce the same structured judgment:
 * {@code {claim_id, validity, failure_modes, evidence}}.
 */
@Component
public class DagVerifierActivity implements Activity {

    public static final String TYPE = "dag.verify";
    private static final Pattern JSON_BLOCK = Pattern.compile("[{\\[][\\s\\S]*[}\\]]");

    private final Json json;

    public DagVerifierActivity(Json json) {
        this.json = json;
    }

    public record Input(int claimId, String check, String statement, String reasoning, String conclusion) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        Input in = ctx.input(inputJson, Input.class);
        List<String> failures = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        double validity = switch (in.check() == null ? "" : in.check()) {
            case "LOGIC_CONSISTENCY" -> logicConsistency(in, failures, evidence);
            case "SCHEMA_ALIGNMENT" -> schemaAlignment(in, failures, evidence);
            case "EVIDENCE_GROUNDING" -> evidenceGrounding(in, failures, evidence);
            case "CONSTRAINT_CHECK" -> constraintCheck(in, failures, evidence);
            default -> {
                evidence.add("unknown check treated as neutral evidence");
                yield 0.5;
            }
        };
        return new VerifierOutput(in.claimId(), in.check(), validity, failures, evidence);
    }

    /** Does the conclusion agree with (not reverse) the reasoning's decision polarity? */
    private double logicConsistency(Input in, List<String> failures, List<String> evidence) {
        DecisionPolarity.IntentResult intent =
                DecisionPolarity.consistency(in.reasoning(), in.conclusion());
        if (intent.reversed()) {
            failures.add("conclusion reverses the decision reached in the reasoning");
            evidence.add(intent.explanation());
            return 0.1;
        }
        evidence.add(intent.explanation());
        return intent.consistent() ? Math.max(0.75, intent.score()) : 0.55;
    }

    /** If the output claims structure (JSON/SQL/config), does it actually parse? */
    private double schemaAlignment(Input in, List<String> failures, List<String> evidence) {
        String text = in.reasoning() + " " + in.conclusion();
        Matcher m = JSON_BLOCK.matcher(text);
        if (!m.find()) {
            evidence.add("no structured block present — nothing to misalign");
            return 0.6;
        }
        try {
            json.mapper().readTree(m.group());
            evidence.add("embedded structured block parses as valid JSON");
            return 0.9;
        } catch (Exception e) {
            failures.add("embedded structured block does not parse: " + firstLine(e.getMessage()));
            return 0.15;
        }
    }

    /** Does the reasoning actually address the claim (topical grounding)? */
    private double evidenceGrounding(Input in, List<String> failures, List<String> evidence) {
        double sim = TextVectors.cosine(in.statement(), in.reasoning());
        evidence.add(String.format("reasoning↔claim topical similarity %.2f", sim));
        if (sim < 0.08) {
            failures.add("reasoning does not address the claim (topic drift)");
            return 0.2;
        }
        return Math.min(0.9, 0.45 + sim);
    }

    /** Are the claim's salient tokens (numbers, identifiers) preserved in the answer? */
    private double constraintCheck(Input in, List<String> failures, List<String> evidence) {
        double preserved = DecisionPolarity.salientTokenPreservation(
                in.statement(), in.reasoning() + " " + in.conclusion());
        evidence.add(String.format("salient-token preservation %.2f", preserved));
        if (preserved < 0.5) {
            failures.add("numeric/identifier constraints from the claim were dropped or altered");
        }
        return Math.max(0.1, Math.min(0.95, preserved));
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "parse error";
        }
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl) : s;
    }
}
