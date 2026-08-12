package io.continuum.gateway.openai;

import io.continuum.quality.QualityGateService;
import io.continuum.uncertainty.SemanticUncertaintyService;
import org.springframework.stereotype.Component;

/**
 * Decides, per account, whether a streamed request can be true passthrough.
 *
 * <p>The honest constraint: a feature that judges a finished answer cannot run
 * on an answer that has already been sent. If the quality gate may rewrite the
 * text, or confidence needs several samples of it, then streaming the first
 * draft and correcting it afterwards is worse than waiting — the caller has
 * already rendered it.
 *
 * <p>So this reports which mode applies, the controller streams accordingly,
 * and the mode is named on the response. A caller seeing a slow first token can
 * find out why in the payload rather than filing a latency bug.
 */
@Component
public class StreamPolicy {

    public enum Mode {
        /** Tokens leave as the provider produces them. */
        PASSTHROUGH("passthrough"),
        /**
         * The pipeline runs to completion first, because something downstream is
         * entitled to change the answer.
         */
        BUFFERED("buffered");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final QualityGateService quality;
    private final SemanticUncertaintyService uncertainty;

    public StreamPolicy(QualityGateService quality, SemanticUncertaintyService uncertainty) {
        this.quality = quality;
        this.uncertainty = uncertainty;
    }

    public Mode modeFor(String developerId) {
        if (rewritesAnswers(developerId) || resamplesAnswers(developerId)) {
            return Mode.BUFFERED;
        }
        return Mode.PASSTHROUGH;
    }

    /**
     * True when the quality gate is in a mode that can replace the answer.
     *
     * <p>MONITOR only records what it would have done, so it does not force
     * buffering — the whole point of MONITOR is that it changes nothing, and
     * making it silently cost time-to-first-token would make it a mode nobody
     * would leave on long enough to learn anything from.
     */
    private boolean rewritesAnswers(String developerId) {
        try {
            var status = quality.status(developerId);
            return status != null && "ENFORCE".equalsIgnoreCase(String.valueOf(status.get("mode")));
        } catch (RuntimeException e) {
            // Unknown means we cannot promise passthrough is safe.
            return true;
        }
    }

    private boolean resamplesAnswers(String developerId) {
        try {
            var status = uncertainty.status(developerId);
            if (status == null) {
                return false;
            }
            String mode = String.valueOf(status.get("mode"));
            return "ALWAYS".equalsIgnoreCase(mode) || "ADAPTIVE".equalsIgnoreCase(mode);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
