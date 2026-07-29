package io.continuum.counterfactual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What would last week's traffic have cost on a different routing policy?"
 *
 * <p>Deliberately built as a <b>batch evaluator</b>, not a time-travel button in
 * the UI. Replaying one request against a different model and showing the two
 * answers side by side is a demo; answering <em>"replay ten thousand logged
 * requests against this candidate policy and tell me the cost and quality delta
 * before I switch"</em> is a capability, and it is how you would safely tune a
 * cascade threshold without experimenting on live traffic.
 *
 * <p><b>The statistics, and why the honest answer is uncomfortable.</b> Estimating
 * how a policy you did <em>not</em> run would have performed, from logs of the
 * policy you <em>did</em> run, is off-policy evaluation. The standard tool is the
 * doubly robust estimator (Dudík, Langford &amp; Li, ICML 2011), which combines
 *
 * <ul>
 *   <li>a <b>direct method</b> — model each arm's reward and predict; low
 *       variance, biased by however wrong the model is; and</li>
 *   <li>an <b>inverse propensity score</b> correction — reweight the logged
 *       outcomes by how likely the logging policy was to take that action;
 *       unbiased, but only where the logging policy could have taken it.</li>
 * </ul>
 *
 * <p>That second condition is the problem here, and it is not a small one.
 * <b>Continuum's routing is deterministic.</b> For any logged request the
 * propensity of the arm actually chosen is 1 and of every other arm is 0. IPS
 * divides by the propensity, so for a candidate that would have chosen
 * differently the correction is undefined — there is no overlap, and no amount
 * of arithmetic recovers information the logs do not contain.
 *
 * <p>So this evaluator splits the answer in two and refuses to blend them:
 *
 * <table>
 *   <tr><td><b>Agreed</b></td><td>the candidate would have made the same choice.
 *       The logged outcome <em>is</em> the counterfactual outcome. Measured.</td></tr>
 *   <tr><td><b>Diverged</b></td><td>the candidate would have chosen differently.
 *       Estimated from that arm's own history on comparable traffic — the direct
 *       method alone, with no correction available.</td></tr>
 * </table>
 *
 * <p>The report carries both, plus the fraction that had to be estimated.
 * Presenting a single blended number would hide exactly the thing a person about
 * to change their routing needs to know: <b>how much of this is measurement and
 * how much is a guess.</b>
 */
public final class CounterfactualEvaluator {

    private CounterfactualEvaluator() {
    }

    /** One logged request, reduced to what evaluation needs. */
    public record Observation(String provider, String model, double complexity,
                              long latencyMs, int tokens, double costUsd, boolean success) {
    }

    /** What a candidate policy would have done with a request. */
    public interface Policy {
        /** @return {@code provider/model}, or null to mean "same as logged" */
        String choose(Observation o);

        String name();
    }

    /**
     * A candidate that always picks one model.
     *
     * <p>The simplest useful question — "what if everything had gone to the
     * cheap one" — and the one that exposes the overlap problem most starkly.
     */
    public static Policy always(String providerSlashModel) {
        return new Policy() {
            @Override
            public String choose(Observation o) {
                return providerSlashModel;
            }

            @Override
            public String name() {
                return "always " + providerSlashModel;
            }
        };
    }

    /**
     * A candidate that sends easy traffic to one model and hard traffic to
     * another — the shape of a cascade threshold, which is the parameter this
     * evaluator exists to help tune.
     */
    public static Policy threshold(double at, String below, String above) {
        return new Policy() {
            @Override
            public String choose(Observation o) {
                return o.complexity() < at ? below : above;
            }

            @Override
            public String name() {
                return String.format("complexity < %.2f → %s, else %s", at, below, above);
            }
        };
    }

    /** Per-arm history, used as the direct-method model where there is no overlap. */
    private record ArmModel(String arm, int n, double meanCostPerToken, double meanLatency,
                            double successRate) {
    }

    /**
     * @param arm               provider/model the estimate is for
     * @param confident         whether every request on this row is measured
     * @param measuredRequests  how many of them are measured rather than modelled —
     *                          a single boolean would call a row "modelled" when
     *                          nine in ten of its requests were observed directly
     */
    public record Line(String arm, boolean confident, int requests, int measuredRequests,
                       double cost, String basis) {
    }

    /**
     * @param agreedFraction  share of traffic where the candidate agrees with what ran —
     *                        the only part that is measured rather than modelled
     * @param unmodellable    requests the candidate routes to an arm with no history at all
     */
    public record Report(String policy, int requests,
                         double actualCost, double estimatedCost,
                         double measuredActualCost, double measuredCandidateCost,
                         double modelledActualCost, double modelledCandidateCost,
                         double agreedFraction, int unmodellable,
                         List<Line> lines, String verdict, String caveat) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policy", policy);
            m.put("requests", requests);
            m.put("actualCost", actualCost);
            m.put("estimatedCost", estimatedCost);
            m.put("delta", estimatedCost - actualCost);
            m.put("deltaPct", actualCost == 0 ? null
                    : (estimatedCost - actualCost) / actualCost);
            m.put("measuredActualCost", measuredActualCost);
            m.put("measuredCandidateCost", measuredCandidateCost);
            m.put("modelledActualCost", modelledActualCost);
            m.put("modelledCandidateCost", modelledCandidateCost);
            m.put("agreedFraction", Math.round(agreedFraction * 1000) / 1000.0);
            m.put("unmodellable", unmodellable);
            m.put("lines", lines.stream().map(l -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("arm", l.arm());
                x.put("confident", l.confident());
                x.put("requests", l.requests());
                x.put("measuredRequests", l.measuredRequests());
                x.put("cost", l.cost());
                x.put("basis", l.basis());
                return x;
            }).toList());
            m.put("verdict", verdict);
            m.put("caveat", caveat);
            return m;
        }
    }

    /** Complexity band width for "comparable traffic" in the direct method. */
    private static final double BAND = 0.25;

    /**
     * Evaluates {@code policy} against logged traffic.
     *
     * @param log observations, most recent first or otherwise — order is not used
     */
    public static Report evaluate(List<Observation> log, Policy policy) {
        if (log == null || log.isEmpty()) {
            return new Report(policy.name(), 0, 0, 0, 0, 0, 0, 0, 1.0, 0, List.of(),
                    "No logged traffic to replay.",
                    "An evaluation over nothing is not a cautious estimate — it is no estimate.");
        }

        // Direct-method model: each arm's own history, banded by complexity so a
        // cheap model is not credited with the cost profile of hard questions it
        // never saw.
        Map<String, List<Observation>> byArm = new LinkedHashMap<>();
        for (Observation o : log) {
            byArm.computeIfAbsent(arm(o.provider(), o.model()), k -> new ArrayList<>()).add(o);
        }

        double actualCost = 0;
        double candidateCost = 0;
        double measuredActual = 0;
        double measuredCandidate = 0;
        double modelledActual = 0;
        double modelledCandidate = 0;
        int agreed = 0;
        int unmodellable = 0;
        // [0] = requests routed here, [1] = how many of those are measured.
        Map<String, int[]> countByArm = new LinkedHashMap<>();
        Map<String, double[]> costByArm = new LinkedHashMap<>();

        for (Observation o : log) {
            actualCost += o.costUsd();
            String actualArm = arm(o.provider(), o.model());
            String chosen = policy.choose(o);
            String targetArm = chosen == null ? actualArm : chosen;

            double cost;
            boolean confident;
            if (targetArm.equals(actualArm)) {
                // The candidate agrees. The logged outcome IS the counterfactual
                // outcome — no model, no assumption.
                cost = o.costUsd();
                confident = true;
                agreed++;
                measuredActual += o.costUsd();
                measuredCandidate += cost;
            } else {
                Double perToken = meanCostPerToken(byArm.get(targetArm), o.complexity());
                if (perToken == null) {
                    // Nothing ever ran on that arm near this complexity. There is
                    // no estimate to make, and inventing one would be the whole
                    // failure mode of off-policy evaluation.
                    unmodellable++;
                    cost = o.costUsd();
                    confident = false;
                    modelledActual += o.costUsd();
                    modelledCandidate += cost;
                } else {
                    cost = perToken * o.tokens();
                    confident = false;
                    modelledActual += o.costUsd();
                    modelledCandidate += cost;
                }
            }
            candidateCost += cost;

            int[] counts = countByArm.computeIfAbsent(targetArm, k -> new int[2]);
            counts[0]++;
            if (confident) {
                counts[1]++;
            }
            costByArm.computeIfAbsent(targetArm, k -> new double[1])[0] += cost;
        }

        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : countByArm.entrySet()) {
            int n = e.getValue()[0];
            int measured = e.getValue()[1];
            boolean conf = measured == n;
            String basis = conf
                    ? "measured — this is what actually ran"
                    : measured == 0
                            ? "modelled from this arm's history on comparable traffic"
                            : measured + " of " + n + " measured; the rest modelled from this "
                                    + "arm's history on comparable traffic";
            lines.add(new Line(e.getKey(), conf, n, measured, costByArm.get(e.getKey())[0], basis));
        }

        double agreedFraction = (double) agreed / log.size();
        double delta = candidateCost - actualCost;
        String verdict = String.format(
                "%s would have cost $%.6f against $%.6f actually spent — %s%.1f%%.",
                policy.name(), candidateCost, actualCost,
                delta >= 0 ? "+" : "", actualCost == 0 ? 0 : (delta / actualCost) * 100);

        String caveat;
        if (agreedFraction >= 0.999) {
            caveat = "Every request is measured: this candidate would have made the same choice "
                    + "every time, so the figure above is what happened, not an estimate.";
        } else if (unmodellable > 0) {
            caveat = String.format(
                    "%.0f%% of requests are measured; the rest are modelled, and %d of them route "
                            + "to an arm with no comparable history at all — for those the logged "
                            + "cost was carried through unchanged because there is nothing to "
                            + "estimate from. Treat this as a direction, not a number.",
                    agreedFraction * 100, unmodellable);
        } else {
            caveat = String.format(
                    "%.0f%% of requests are measured; the remaining %.0f%% are modelled from each "
                            + "arm's own history. Routing here is deterministic, so there is no "
                            + "propensity correction available for the diverging traffic — that "
                            + "part is a direct-method estimate and its bias is not bounded.",
                    agreedFraction * 100, (1 - agreedFraction) * 100);
        }

        return new Report(policy.name(), log.size(), actualCost, candidateCost,
                measuredActual, measuredCandidate, modelledActual, modelledCandidate,
                agreedFraction, unmodellable, lines, verdict, caveat);
    }

    /** Mean cost per token on {@code arm} for traffic of comparable complexity. */
    private static Double meanCostPerToken(List<Observation> arm, double complexity) {
        if (arm == null || arm.isEmpty()) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (Observation o : arm) {
            if (Math.abs(o.complexity() - complexity) <= BAND && o.tokens() > 0) {
                sum += o.costUsd() / o.tokens();
                n++;
            }
        }
        if (n == 0) {
            // Widen once rather than fail: a band with no neighbours is common on
            // thin logs, and the caller is told this row is modelled either way.
            for (Observation o : arm) {
                if (o.tokens() > 0) {
                    sum += o.costUsd() / o.tokens();
                    n++;
                }
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static String arm(String provider, String model) {
        return (provider == null ? "?" : provider) + "/" + (model == null ? "?" : model);
    }
}
