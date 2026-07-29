package io.continuum.cascade;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether firing the strong model speculatively is worth it — computed from this
 * account's own measured escalation rate.
 *
 * <p>Speculative execution (Leviathan, Kalman &amp; Matias, ICML 2023, generalised
 * across models rather than within one) runs the cheap and strong models at the
 * same time and returns whichever the verifier accepts. It converts the
 * cascade's <em>latency</em> penalty into a <em>cost</em> penalty:
 *
 * <table>
 *   <tr><td>Sequential today</td><td>cost {@code C_cheap + p·C_strong}, latency {@code L_cheap + p·L_strong}</td></tr>
 *   <tr><td>Speculative</td><td>cost {@code C_cheap + C_strong}, latency {@code ≈ L_cheap}</td></tr>
 * </table>
 *
 * <p>where {@code p} is the fraction of requests that escalate. Both differences
 * are driven by the same number, in opposite directions:
 *
 * <ul>
 *   <li>extra spend {@code = (1 − p) · C_strong} — you paid for the strong model
 *       on every request that turned out not to need it;</li>
 *   <li>latency saved {@code = p · L_strong} — on the requests that did escalate,
 *       the strong call was already running instead of starting afterwards.</li>
 * </ul>
 *
 * <p><b>So the feature can tell you whether to use it.</b> At a low escalation
 * rate speculation is almost pure waste; at a high one the sequential cascade is
 * mostly paying for both models anyway and merely doing it slowly. The research
 * dossier that ranked this feature said not to build it until that rate could be
 * measured — it now can be, so this class answers the question rather than
 * leaving it to intuition.
 *
 * <p><b>What it does not know.</b> Whether a millisecond is worth a cent is a
 * product decision, not an arithmetic one. This reports both numbers and a
 * break-even point; it does not pretend there is a universal exchange rate
 * between latency and money.
 */
public final class SpeculationEconomics {

    private SpeculationEconomics() {
    }

    /**
     * Escalation rate above which speculation costs less extra than the share of
     * traffic it accelerates — the point where the trade stops being lopsided.
     *
     * <p>Not a law. It is where {@code (1 − p) < p}, i.e. where more requests
     * benefit from the parallel call than pay for it needlessly.
     */
    public static final double BREAK_EVEN = 0.5;

    /**
     * @param escalationRate    measured fraction of requests that escalated
     * @param extraSpendPct     additional spend as a fraction of what the cascade costs today
     * @param latencySavedMs    mean latency the parallel call removes per request
     * @param worthwhile        whether the numbers favour turning it on
     */
    public record Verdict(double escalationRate, double extraSpendUsd, double extraSpendPct,
                          double latencySavedMs, boolean worthwhile, String summary) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("escalationRate", Math.round(escalationRate * 1000) / 1000.0);
            m.put("breakEven", BREAK_EVEN);
            m.put("extraSpendUsd", extraSpendUsd);
            m.put("extraSpendPct", Double.isNaN(extraSpendPct) ? null
                    : Math.round(extraSpendPct * 1000) / 1000.0);
            m.put("latencySavedMs", Math.round(latencySavedMs));
            m.put("worthwhile", worthwhile);
            m.put("summary", summary);
            return m;
        }
    }

    /** Below this many observed requests the rate is noise, not a measurement. */
    public static final int MIN_SAMPLE = 30;

    /**
     * Advises from measured traffic.
     *
     * @param requests         how many cascade decisions were observed
     * @param escalated        how many of them escalated
     * @param strongCostUsd    mean cost of one strong-tier call
     * @param strongLatencyMs  mean latency of one strong-tier call
     * @param currentSpendUsd  what the cascade actually spent over those requests
     */
    public static Verdict advise(long requests, long escalated, double strongCostUsd,
                                 double strongLatencyMs, double currentSpendUsd) {
        if (requests < MIN_SAMPLE) {
            return new Verdict(0, 0, Double.NaN, 0, false, String.format(
                    "Only %d cascade decisions recorded. Below %d the escalation rate is noise, "
                            + "and turning speculation on or off from it would be guessing. "
                            + "Send more traffic through the cascade first.",
                    requests, MIN_SAMPLE));
        }

        double p = (double) escalated / requests;
        // Every request that did NOT escalate would have paid for the strong
        // model anyway under speculation.
        double extraSpend = (1 - p) * requests * strongCostUsd;
        double extraPct = currentSpendUsd <= 0 ? Double.NaN : extraSpend / currentSpendUsd;
        // Every request that DID escalate stops waiting for a second round trip.
        double latencySaved = p * strongLatencyMs;

        boolean worthwhile = p >= BREAK_EVEN;
        String summary;
        if (worthwhile) {
            summary = String.format(
                    "%.0f%% of requests escalate, so the cascade is already paying for both models "
                            + "on most traffic — and doing it one after the other. Speculation would "
                            + "add about $%.5f (%s) and remove roughly %dms of waiting per request.",
                    p * 100, extraSpend,
                    Double.isNaN(extraPct) ? "an unknown share" : String.format("%.0f%% more", extraPct * 100),
                    Math.round(latencySaved));
        } else {
            summary = String.format(
                    "Only %.0f%% of requests escalate, so speculation would pay for the strong model "
                            + "on the %.0f%% that never needed it — about $%.5f more (%s) to save "
                            + "roughly %dms per request. At this rate it is mostly waste; it becomes "
                            + "a reasonable trade above %.0f%%.",
                    p * 100, (1 - p) * 100, extraSpend,
                    Double.isNaN(extraPct) ? "share unknown" : String.format("%.0f%% more", extraPct * 100),
                    Math.round(latencySaved), BREAK_EVEN * 100);
        }
        return new Verdict(p, extraSpend, extraPct, latencySaved, worthwhile, summary);
    }
}
