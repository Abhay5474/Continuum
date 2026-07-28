package io.continuum.degradation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to do when the full-quality path cannot be delivered.
 *
 * <p>Today the answer is a 502. Every provider in the chain failed, so the
 * gateway throws and the calling application gets nothing — which is the correct
 * behaviour for a system with no other options and the wrong one for a system
 * with several.
 *
 * <p>A ladder makes the options explicit and ordered, so degradation is a
 * decision rather than an accident:
 *
 * <table>
 *   <tr><td>{@link Rung#FULL}</td><td>What was asked for.</td></tr>
 *   <tr><td>{@link Rung#CACHED}</td><td>A previous answer to an equivalent question — stale, but true when it was written.</td></tr>
 *   <tr><td>{@link Rung#STATIC}</td><td>An honest message saying no answer could be produced.</td></tr>
 * </table>
 *
 * <p><b>The response always says which rung it came from.</b> A degraded answer
 * presented as a normal one is worse than an error: the caller cannot tell that
 * it should retry, warn its user, or decline to act on it. Silently succeeding
 * is the failure mode this feature could most easily become.
 *
 * <p>There is no "cheaper model" rung, and the omission is deliberate. Falling
 * back to a smaller model is already what the fallback chain does, several times
 * over, before this ladder is ever reached — adding it here would present the
 * chain's ordinary work as a degradation event.
 */
public final class DegradationLadder {

    public enum Rung {
        /** The answer the request asked for. */
        FULL,
        /** A previous answer to an equivalent question. */
        CACHED,
        /** No answer; an honest message. */
        STATIC;

        /** Whether an answer from this rung is the one that was asked for. */
        public boolean isDegraded() {
            return this != FULL;
        }
    }

    /**
     * @param rung   where the answer came from
     * @param reason written for the developer whose request this was
     */
    public record Outcome(Rung rung, String answer, String reason) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rung", rung.name());
            m.put("degraded", rung.isDegraded());
            m.put("reason", reason);
            return m;
        }
    }

    /** What the bottom rung says. Deliberately not an apology-shaped nothing. */
    public static final String STATIC_ANSWER =
            "No answer could be produced for this request — every available model failed. "
                    + "This is a temporary service problem, not a judgement about the question. "
                    + "Please retry shortly.";

    private DegradationLadder() {
    }

    /**
     * The best rung that can actually be filled.
     *
     * @param cached a previous answer to an equivalent question, or null
     * @param failure why the full path did not work
     */
    public static Outcome descend(String cached, String failure) {
        if (cached != null && !cached.isBlank()) {
            return new Outcome(Rung.CACHED, cached,
                    "Every model failed (" + shorten(failure) + "), so a previous answer to an "
                            + "equivalent question was served instead. It may be out of date.");
        }
        return new Outcome(Rung.STATIC, STATIC_ANSWER,
                "Every model failed (" + shorten(failure) + ") and no equivalent answer was "
                        + "cached, so nothing could be served.");
    }

    /**
     * Provider errors carry stack detail and sometimes credentials-adjacent
     * text. The caller gets a short cause, not an upstream's internals.
     */
    private static String shorten(String failure) {
        if (failure == null || failure.isBlank()) {
            return "no cause reported";
        }
        String f = failure.strip();
        int nl = f.indexOf('\n');
        if (nl > 0) {
            f = f.substring(0, nl);
        }
        return f.length() > 120 ? f.substring(0, 120) + "…" : f;
    }
}
