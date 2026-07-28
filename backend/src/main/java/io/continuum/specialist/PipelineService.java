package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.gateway.GatewayDtos;
import io.continuum.gateway.GatewayService;
import io.continuum.persistence.entity.PipelineEntity;
import io.continuum.persistence.entity.SpecialistEntity;
import io.continuum.persistence.entity.TraceStepEntity;
import io.continuum.persistence.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a pipeline: the application's raw input, through the specialists, into
 * structured context, and on to the model.
 *
 * <p>The external application sends an image and receives advice. It never
 * learns that a detector ran, what it was called, or who hosts it — that is the
 * point of the layer. What it does receive is a trace id, so it can show its own
 * user the chain if it wants to.
 *
 * <p>Execution is synchronous. Compiling to the durable workflow engine would
 * make this replayable and crash-proof, which is right for a long back-office
 * job and wrong for a request the caller is holding a connection open for.
 * Durability belongs to a separate entry point, not to a different pipeline
 * model.
 *
 * <p>A specialist that fails does not fail the pipeline. Its absence is stated
 * in the context so the model knows its evidence is incomplete, and the answer
 * is still produced — a partial answer with a caveat beats an error page.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository repo;
    private final SpecialistService specialists;
    private final SpecialistInvoker invoker;
    private final GatewayService gateway;
    private final TraceRecorder traces;
    private final ObjectMapper mapper;

    public PipelineService(PipelineRepository repo, SpecialistService specialists,
                           SpecialistInvoker invoker, GatewayService gateway,
                           TraceRecorder traces, ObjectMapper mapper) {
        this.repo = repo;
        this.specialists = specialists;
        this.invoker = invoker;
        this.gateway = gateway;
        this.traces = traces;
        this.mapper = mapper;
    }

    /**
     * What the calling application gets back.
     *
     * @param policy     the confidence decision, or null when the policy is off
     * @param compliance whether the model actually did what the policy asked,
     *                   or null when nothing was asked of it
     */
    public record Run(String response, String traceId, int findings, double topConfidence,
                      boolean anythingFound, boolean analysisRan, String model, double cost,
                      long latencyMs, List<Map<String, Object>> chain,
                      Map<String, Object> policy, Map<String, Object> compliance,
                      Map<String, Object> verification) {
    }

    @Transactional
    public Run run(String developerId, String pipelineName, Map<String, Object> input,
                   String userPrompt) {
        long start = System.nanoTime();
        PipelineEntity p = repo.findByDeveloperIdAndName(developerId, pipelineName)
                .orElseThrow(() -> new SpecialistConnectionService.InvalidConnectionException(
                        "No pipeline called '" + pipelineName + "'."));
        if (!p.isEnabled()) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "Pipeline '" + pipelineName + "' is not enabled.");
        }

        String traceId = traces.newTrace();
        traces.step(traceId, developerId, TraceStepEntity.Kind.INPUT,
                describeInput(p.getInputKind()),
                Map.of("kind", p.getInputKind(), "pipeline", p.getName()), "OK", null, 0, 0);

        // --- specialists ----------------------------------------------------
        List<ContextBuilder.StepResult> results = new ArrayList<>();
        int findingsSoFar = 0;
        int ranSoFar = 0;
        int skipped = 0;
        for (PipelineStep step : steps(p)) {
            SpecialistEntity s;
            try {
                s = specialists.require(developerId, step.specialistId());
            } catch (RuntimeException e) {
                // A deleted specialist should not break a pipeline that still
                // has others; note it and carry on.
                log.warn("Pipeline {} references missing specialist {}", p.getName(),
                        step.specialistId());
                continue;
            }

            // With routing off every step runs, whatever its condition — the
            // behaviour every pipeline had before routing existed.
            StepRouter.Decision route = p.isRoutingEnabled()
                    ? StepRouter.decide(step, findingsSoFar, ranSoFar, userPrompt, p.getInputKind())
                    : new StepRouter.Decision(true, "Routing is off, so every step runs.");

            if (!route.run()) {
                // Recorded, not silent. A step that did not run and a step that
                // ran and found nothing look identical in an answer and need
                // completely different fixes.
                skipped++;
                traces.step(traceId, developerId, TraceStepEntity.Kind.SPECIALIST,
                        s.getName() + " — skipped",
                        Map.of("condition", step.when().name(),
                                "pattern", String.valueOf(step.pattern()),
                                "reason", route.reason()),
                        "SKIPPED", null, 0, 0);
                continue;
            }

            SpecialistInvoker.Result r = invoker.invoke(s, input, traceId);
            results.add(new ContextBuilder.StepResult(s.getName(), r.findings(), r.dropped(), r.error()));
            ranSoFar++;
            if (r.error() == null) {
                findingsSoFar += r.findings().size();
            }
        }

        // --- context --------------------------------------------------------
        ContextBuilder.Context ctx = ContextBuilder.build(p.getName(), userPrompt, results);
        traces.step(traceId, developerId, TraceStepEntity.Kind.ENRICHMENT,
                ctx.analysisRan() ? "Findings structured for the model"
                        : "No analysis available — model told so explicitly",
                // The assembled prose is kept, not just the facts it was built
                // from. When an answer is wrong, the question is almost always
                // "what was the model actually told?" — and a reconstruction
                // from the structured form is not the same artefact.
                Map.of("findings", ctx.totalFindings(), "structured", ctx.structured(),
                        "prompt", ctx.prompt()),
                ctx.analysisRan() ? "OK" : "DEGRADED",
                ctx.anythingFound() ? ctx.topConfidence() : null, 0, 0);

        // --- policy ---------------------------------------------------------
        // Between the evidence and the model: how strong is what we have, and
        // what is the model allowed to do with it. Off unless the developer
        // turned it on for this pipeline.
        ConfidencePolicy.Decision decision = p.isPolicyEnabled()
                ? ConfidencePolicy.decide(ctx, p.getStrongThreshold(), p.getWeakThreshold(),
                        p.isDeclineOnNoEvidence())
                : null;
        if (decision != null) {
            traces.step(traceId, developerId, TraceStepEntity.Kind.POLICY,
                    policyLabel(decision), decision.describe(),
                    decision.declined() ? "DECLINED"
                            : decision.action() == ConfidencePolicy.Action.PASS ? "OK" : "CONSTRAINED",
                    decision.evidence() > 0 ? decision.evidence() : null, 0, 0);
        }

        // A decline never reaches a model. Spending a call to be told to say
        // "I cannot assess this" is money for a sentence already known, and it
        // leaves room for the model to answer anyway.
        if (decision != null && decision.declined()) {
            String canned = ConfidencePolicy.declineMessage(decision.band());
            long ms = (System.nanoTime() - start) / 1_000_000;
            traces.step(traceId, developerId, TraceStepEntity.Kind.OUTPUT,
                    "Declined without calling a model",
                    Map.of("characters", canned.length()), "DECLINED", null, 0, 0);
            p.recordRun();
            repo.save(p);
            Map<String, Object> declinedChain = traces.trace(developerId, traceId);
            traces.finish(traceId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> declinedSteps =
                    (List<Map<String, Object>>) declinedChain.get("steps");
            return new Run(canned, traceId, ctx.totalFindings(), ctx.topConfidence(),
                    ctx.anythingFound(), ctx.analysisRan(), null, 0, ms, declinedSteps,
                    decision.describe(), null, null);
        }

        // --- model ----------------------------------------------------------
        List<GatewayDtos.Message> messages = new ArrayList<>();
        if (p.getSystemPrompt() != null && !p.getSystemPrompt().isBlank()) {
            messages.add(new GatewayDtos.Message("system", p.getSystemPrompt()));
        }
        String userMessage = ctx.prompt()
                + (decision == null ? "" : decision.instruction());
        messages.add(new GatewayDtos.Message("user", userMessage));

        long modelStart = System.nanoTime();
        GatewayDtos.ChatResponse answer = gateway.chat(developerId,
                new GatewayDtos.ChatRequest("auto", messages, 800, 0.3, null, false, null, null));
        long modelMs = (System.nanoTime() - modelStart) / 1_000_000;

        traces.step(traceId, developerId, TraceStepEntity.Kind.MODEL,
                answer.model() == null ? "Language model" : answer.model(),
                Map.of("provider", String.valueOf(answer.provider()),
                        "reason", String.valueOf(answer.routingReason())),
                "OK", answer.confidence(), answer.cost(), modelMs);

        // --- did it comply? --------------------------------------------------
        // The policy asked; nothing made it binding. Measured rather than
        // assumed, because a page reporting "hedged" on the strength of having
        // requested one is reporting its own intent as an observation.
        HedgeDetector.Compliance compliance = decision == null ? null
                : HedgeDetector.check(decision.action(), answer.response());
        if (compliance != null && compliance.checked()) {
            traces.step(traceId, developerId, TraceStepEntity.Kind.VERIFY,
                    compliance.complied() ? "Model followed the policy"
                            : "Model did NOT follow the policy",
                    compliance.describe(), compliance.complied() ? "OK" : "IGNORED", null, 0, 0);
        }

        // --- does the advice match the findings? ------------------------------
        // The policy and the compliance check are both about the instruction.
        // Neither asks whether the advice is anchored to what was actually
        // found — an answer can hedge beautifully and still describe an injury
        // nobody detected.
        String finalAnswer = answer.response();
        Map<String, Object> verification = null;
        if (p.getVerificationMode() != AnswerVerifier.Mode.OFF) {
            AnswerVerifier.Result v = AnswerVerifier.check(finalAnswer, ctx, userPrompt,
                    p.getWeakThreshold());
            boolean replace = p.getVerificationMode() == AnswerVerifier.Mode.ENFORCE
                    && v.verdict() == AnswerVerifier.Verdict.FAIL;
            if (replace) {
                finalAnswer = AnswerVerifier.replacement(ctx);
                v = new AnswerVerifier.Result(v.verdict(), v.issues(), v.covered(), v.uncovered(),
                        true, v.method());
            }
            verification = v.describe();
            traces.step(traceId, developerId, TraceStepEntity.Kind.VERIFY,
                    verifyLabel(v.verdict(), replace), verification,
                    switch (v.verdict()) {
                        case OK -> "OK";
                        case WARN -> "WARNED";
                        case FAIL -> replace ? "REPLACED" : "FAILED";
                    }, null, 0, 0);
        }

        long totalMs = (System.nanoTime() - start) / 1_000_000;
        traces.step(traceId, developerId, TraceStepEntity.Kind.OUTPUT, "Answer returned",
                Map.of("characters", finalAnswer == null ? 0 : finalAnswer.length()),
                "OK", null, 0, 0);

        p.recordRun();
        repo.save(p);
        Map<String, Object> chain = traces.trace(developerId, traceId);
        traces.finish(traceId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) chain.get("steps");
        return new Run(finalAnswer, traceId, ctx.totalFindings(), ctx.topConfidence(),
                ctx.anythingFound(), ctx.analysisRan(), answer.model(), answer.cost(), totalMs, steps,
                decision == null ? null : decision.describe(),
                compliance == null || !compliance.checked() ? null : compliance.describe(),
                verification);
    }

    // --- configuration -------------------------------------------------------

    @Transactional
    public Map<String, Object> create(String developerId, String name, String description,
                                      String inputKind, String systemPrompt,
                                      List<PipelineStep> steps) {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,120}")) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "A pipeline name may contain letters, digits, underscore and hyphen — "
                            + "it appears in the URL your application calls.");
        }
        if (repo.findByDeveloperIdAndName(developerId, name).isPresent()) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "You already have a pipeline called '" + name + "'.");
        }
        return describe(repo.save(new PipelineEntity(developerId, name, description, inputKind,
                systemPrompt, writeSteps(steps))));
    }

    @Transactional
    public Map<String, Object> update(String developerId, Long id, String description,
                                      String systemPrompt, List<PipelineStep> steps, Boolean enabled,
                                      Boolean policyEnabled, Double strongThreshold,
                                      Double weakThreshold, Boolean declineOnNoEvidence,
                                      Boolean routingEnabled, String verificationMode) {
        PipelineEntity p = require(developerId, id);
        if (strongThreshold != null || weakThreshold != null) {
            // Set together: the entity refuses weak > strong, which would leave
            // no middle band and silently turn hedging off.
            p.setThresholds(
                    strongThreshold == null ? p.getStrongThreshold() : strongThreshold,
                    weakThreshold == null ? p.getWeakThreshold() : weakThreshold);
        }
        if (policyEnabled != null) {
            p.setPolicyEnabled(policyEnabled);
        }
        if (declineOnNoEvidence != null) {
            p.setDeclineOnNoEvidence(declineOnNoEvidence);
        }
        if (description != null) {
            p.setDescription(description);
        }
        if (systemPrompt != null) {
            p.setSystemPrompt(systemPrompt);
        }
        if (routingEnabled != null) {
            p.setRoutingEnabled(routingEnabled);
        }
        if (verificationMode != null) {
            try {
                p.setVerificationMode(AnswerVerifier.Mode.valueOf(verificationMode));
            } catch (IllegalArgumentException e) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "Verification is OFF, MONITOR or ENFORCE.");
            }
        }
        if (steps != null) {
            validateSteps(developerId, steps);
            p.setSteps(writeSteps(steps));
        }
        if (enabled != null) {
            if (enabled && stepIds(p).isEmpty()) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "A pipeline needs at least one specialist before it can be enabled.");
            }
            p.setEnabled(enabled);
        }
        repo.save(p);
        return describe(p);
    }

    /**
     * Every step must be this developer's, must have been probed, and must carry
     * a condition that can actually be evaluated where it sits.
     */
    private void validateSteps(String developerId, List<PipelineStep> steps) {
        for (int i = 0; i < steps.size(); i++) {
            PipelineStep step = steps.get(i);
            SpecialistEntity s = specialists.require(developerId, step.specialistId());
            if (s.getStatus() == SpecialistEntity.Status.DRAFT) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "Specialist '" + s.getName() + "' has not been probed yet.");
            }
            // Caught here rather than at request time. "Only if the previous
            // step found something" in first position is not a condition that
            // can be false — it is a condition with nothing to refer to, and
            // discovering that on a customer's request is too late.
            if (i == 0 && step.when().needsPredecessor()) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "'" + s.getName() + "' is first, so there is no previous step for its "
                                + "condition to look at. Move it down, or set it to run always.");
            }
            if (step.when() == PipelineStep.Condition.IF_PROMPT_MATCHES
                    && !StepRouter.validPattern(step.pattern())) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "'" + s.getName() + "' matches on a pattern, but /" + step.pattern()
                                + "/ is not a valid regular expression.");
            }
            if (step.when() == PipelineStep.Condition.IF_INPUT_IS
                    && (step.pattern() == null || step.pattern().isBlank())) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "'" + s.getName() + "' runs for one input kind, but none was given.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String developerId) {
        return repo.findByDeveloperIdOrderByNameAsc(developerId).stream().map(this::describe).toList();
    }

    @Transactional(readOnly = true)
    public PipelineEntity require(String developerId, Long id) {
        return repo.findByIdAndDeveloperId(id, developerId)
                .orElseThrow(() -> new SpecialistConnectionService.InvalidConnectionException(
                        "No such pipeline."));
    }

    @Transactional
    public void delete(String developerId, Long id) {
        repo.delete(require(developerId, id));
    }

    public Map<String, Object> describe(PipelineEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("inputKind", p.getInputKind());
        m.put("systemPrompt", p.getSystemPrompt());
        // Both shapes: `steps` stays a bare id array so anything reading it
        // before routing existed keeps working, and `routing` carries the
        // conditions alongside it.
        m.put("steps", stepIds(p));
        m.put("routing", steps(p).stream().map(PipelineStep::describe).toList());
        m.put("routingEnabled", p.isRoutingEnabled());
        m.put("verificationMode", p.getVerificationMode().name());
        m.put("enabled", p.isEnabled());
        m.put("policyEnabled", p.isPolicyEnabled());
        m.put("strongThreshold", p.getStrongThreshold());
        m.put("weakThreshold", p.getWeakThreshold());
        m.put("declineOnNoEvidence", p.isDeclineOnNoEvidence());
        m.put("runs", p.getRuns());
        m.put("createdAt", p.getCreatedAt());
        return m;
    }

    /**
     * Reads either shape.
     *
     * <p>Before routing, steps were a bare array of ids: {@code [3, 7]}. They are
     * now objects carrying a condition. Both are accepted, so no stored pipeline
     * needed rewriting and a rollback does not strand rows the previous build
     * cannot parse. A bare id means {@code ALWAYS}, which is what it always did.
     */
    List<PipelineStep> steps(PipelineEntity p) {
        List<PipelineStep> out = new ArrayList<>();
        try {
            for (Object o : mapper.readValue(p.getSteps(), List.class)) {
                if (o instanceof Number n) {
                    out.add(PipelineStep.always(n.longValue()));
                } else if (o instanceof Map<?, ?> m) {
                    Object id = m.get("specialistId");
                    if (!(id instanceof Number n)) {
                        continue;
                    }
                    PipelineStep.Condition when;
                    try {
                        when = m.get("when") == null ? PipelineStep.Condition.ALWAYS
                                : PipelineStep.Condition.valueOf(String.valueOf(m.get("when")));
                    } catch (IllegalArgumentException e) {
                        // An unknown condition from a newer build must not make a
                        // step vanish. Running is the pre-routing behaviour.
                        when = PipelineStep.Condition.ALWAYS;
                    }
                    Object pattern = m.get("pattern");
                    out.add(new PipelineStep(n.longValue(), when,
                            pattern == null ? null : String.valueOf(pattern)));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private List<Long> stepIds(PipelineEntity p) {
        return steps(p).stream().map(PipelineStep::specialistId).toList();
    }

    private String writeSteps(List<PipelineStep> steps) {
        try {
            return mapper.writeValueAsString(
                    steps == null ? List.of() : steps.stream().map(PipelineStep::describe).toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String verifyLabel(AnswerVerifier.Verdict verdict, boolean replaced) {
        if (replaced) {
            return "Answer did not match the findings — replaced";
        }
        return switch (verdict) {
            case OK -> "Answer matches the findings";
            case WARN -> "Answer matches, with something worth noting";
            case FAIL -> "Answer does NOT match the findings";
        };
    }

    /** Written for a person reading the chain, not for a machine parsing it. */
    private static String policyLabel(ConfidencePolicy.Decision d) {
        return switch (d.action()) {
            case PASS -> "Evidence is strong — answering directly";
            case HEDGE -> "Evidence is moderate — model told to state its uncertainty";
            case ASK_FOR_BETTER_INPUT -> d.band() == ConfidencePolicy.Band.UNAVAILABLE
                    ? "No analysis available — model told not to present an all-clear"
                    : "Evidence too weak to advise on — model told to ask for better input";
            case DECLINE -> "Declined — not enough evidence to involve a model";
        };
    }

    private static String describeInput(String kind) {
        return switch (kind == null ? "image" : kind) {
            case "text" -> "Text received";
            case "json" -> "Data received";
            case "audio" -> "Audio received";
            default -> "Image received";
        };
    }
}
