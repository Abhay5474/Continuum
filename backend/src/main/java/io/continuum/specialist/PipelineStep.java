package io.continuum.specialist;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One specialist in a pipeline, and when it should run.
 *
 * <p>Until now every specialist in a pipeline ran on every request. That is
 * right for one specialist and wasteful for three: a pipeline that OCRs, then
 * transcribes, then detects objects pays for all three on an input that was only
 * ever going to need one.
 *
 * <p>The two conditions worth having are the cascade in both directions.
 * {@code IF_PREVIOUS_EMPTY} is escalation — screen with something cheap, and
 * only reach for the expensive model when the cheap one saw nothing.
 * {@code IF_PREVIOUS_FOUND} is drill-down — a general detector first, and a
 * specific classifier only once there is something to classify. Between them
 * they cover most of why anyone would want more than one specialist.
 *
 * @param specialistId which specialist
 * @param when         the condition
 * @param pattern      the condition's argument: a regular expression for
 *                     {@code IF_PROMPT_MATCHES}, an input kind for
 *                     {@code IF_INPUT_IS}, unused otherwise
 */
public record PipelineStep(Long specialistId, Condition when, String pattern) {

    public enum Condition {
        /** Always. The default, and what every pre-routing pipeline does. */
        ALWAYS,
        /** Only if an earlier specialist reported something — drill down. */
        IF_PREVIOUS_FOUND,
        /** Only if no earlier specialist reported anything — escalate. */
        IF_PREVIOUS_EMPTY,
        /** Only if the user's own question matches a pattern. */
        IF_PROMPT_MATCHES,
        /** Only for one kind of input, in a pipeline that accepts several. */
        IF_INPUT_IS;

        /** Whether this condition can only be evaluated after something has run. */
        public boolean needsPredecessor() {
            return this == IF_PREVIOUS_FOUND || this == IF_PREVIOUS_EMPTY;
        }
    }

    public PipelineStep {
        if (when == null) {
            when = Condition.ALWAYS;
        }
    }

    public static PipelineStep always(Long id) {
        return new PipelineStep(id, Condition.ALWAYS, null);
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("specialistId", specialistId);
        m.put("when", when.name());
        m.put("pattern", pattern);
        return m;
    }
}
