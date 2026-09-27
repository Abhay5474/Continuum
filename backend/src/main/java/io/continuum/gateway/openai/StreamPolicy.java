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
    private final org.springframework.beans.factory.ObjectProvider<io.continuum.cascade.ResponseCascadeService> cascade;
    private final org.springframework.beans.factory.ObjectProvider<io.continuum.firewall.PromptFirewallService> firewall;
    private final org.springframework.beans.factory.ObjectProvider<io.continuum.dag.ConsensusDagService> consensus;
    private final org.springframework.beans.factory.ObjectProvider<io.continuum.hedging.HedgingService> hedging;
    private final org.springframework.beans.factory.ObjectProvider<io.continuum.mmu.ContextMMU> mmu;
    private final org.springframework.beans.factory.ObjectProvider<io.continuum.aichaos.AiChaosEngine> aiChaos;

    public StreamPolicy(QualityGateService quality, SemanticUncertaintyService uncertainty,
                        org.springframework.beans.factory.ObjectProvider<io.continuum.cascade.ResponseCascadeService> cascade,
                        org.springframework.beans.factory.ObjectProvider<io.continuum.firewall.PromptFirewallService> firewall,
                        org.springframework.beans.factory.ObjectProvider<io.continuum.dag.ConsensusDagService> consensus,
                        org.springframework.beans.factory.ObjectProvider<io.continuum.hedging.HedgingService> hedging,
                        org.springframework.beans.factory.ObjectProvider<io.continuum.mmu.ContextMMU> mmu,
                        org.springframework.beans.factory.ObjectProvider<io.continuum.aichaos.AiChaosEngine> aiChaos) {
        this.quality = quality;
        this.uncertainty = uncertainty;
        this.cascade = cascade;
        this.firewall = firewall;
        this.consensus = consensus;
        this.hedging = hedging;
        this.mmu = mmu;
        this.aiChaos = aiChaos;
    }

    public Mode modeFor(String developerId) {
        if (rewritesAnswers(developerId) || resamplesAnswers(developerId) || needsWholeAnswer(developerId)) {
            return Mode.BUFFERED;
        }
        return Mode.PASSTHROUGH;
    }

    /**
     * The other features that act on a finished answer, or produce one some
     * other way than a single provider streaming it:
     * <ul>
     *   <li>the cascade judges a cheap answer before choosing to pay for a better one;</li>
     *   <li>the prompt firewall redacts secrets from the answer before it leaves;</li>
     *   <li>the consensus DAG answers from several verified drafts;</li>
     *   <li>hedging races two providers and keeps the first finished answer;</li>
     *   <li>the context MMU may re-dispatch after the model asks for a paged segment;</li>
     *   <li>AI chaos corrupts answers on purpose, after they are generated.</li>
     * </ul>
     */
    private boolean needsWholeAnswer(String developerId) {
        try {
            return on(cascade, c -> c.enabledFor(developerId))
                    || on(firewall, f -> f.enabledFor(developerId))
                    || on(consensus, d -> d.enabledFor(developerId))
                    || on(hedging, io.continuum.hedging.HedgingService::isEnabled)
                    || on(mmu, m -> m.enabledFor(developerId))
                    || on(aiChaos, a -> a.isActive(developerId));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static <T> boolean on(org.springframework.beans.factory.ObjectProvider<T> p,
                                  java.util.function.Predicate<T> test) {
        T bean = p.getIfAvailable();
        return bean != null && test.test(bean);
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
