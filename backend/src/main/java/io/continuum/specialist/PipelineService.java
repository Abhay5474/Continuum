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

    /** What the calling application gets back. */
    public record Run(String response, String traceId, int findings, double topConfidence,
                      boolean anythingFound, boolean analysisRan, String model, double cost,
                      long latencyMs, List<Map<String, Object>> chain) {
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
        for (Long specialistId : stepIds(p)) {
            SpecialistEntity s;
            try {
                s = specialists.require(developerId, specialistId);
            } catch (RuntimeException e) {
                // A deleted specialist should not break a pipeline that still
                // has others; note it and carry on.
                log.warn("Pipeline {} references missing specialist {}", p.getName(), specialistId);
                continue;
            }
            SpecialistInvoker.Result r = invoker.invoke(s, input, traceId);
            results.add(new ContextBuilder.StepResult(s.getName(), r.findings(), r.dropped(), r.error()));
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

        // --- model ----------------------------------------------------------
        List<GatewayDtos.Message> messages = new ArrayList<>();
        if (p.getSystemPrompt() != null && !p.getSystemPrompt().isBlank()) {
            messages.add(new GatewayDtos.Message("system", p.getSystemPrompt()));
        }
        messages.add(new GatewayDtos.Message("user", ctx.prompt()));

        long modelStart = System.nanoTime();
        GatewayDtos.ChatResponse answer = gateway.chat(developerId,
                new GatewayDtos.ChatRequest("auto", messages, 800, 0.3, null, false, null));
        long modelMs = (System.nanoTime() - modelStart) / 1_000_000;

        traces.step(traceId, developerId, TraceStepEntity.Kind.MODEL,
                answer.model() == null ? "Language model" : answer.model(),
                Map.of("provider", String.valueOf(answer.provider()),
                        "reason", String.valueOf(answer.routingReason())),
                "OK", answer.confidence(), answer.cost(), modelMs);

        long totalMs = (System.nanoTime() - start) / 1_000_000;
        traces.step(traceId, developerId, TraceStepEntity.Kind.OUTPUT, "Answer returned",
                Map.of("characters", answer.response() == null ? 0 : answer.response().length()),
                "OK", null, 0, 0);

        p.recordRun();
        repo.save(p);
        Map<String, Object> chain = traces.trace(developerId, traceId);
        traces.finish(traceId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) chain.get("steps");
        return new Run(answer.response(), traceId, ctx.totalFindings(), ctx.topConfidence(),
                ctx.anythingFound(), ctx.analysisRan(), answer.model(), answer.cost(), totalMs, steps);
    }

    // --- configuration -------------------------------------------------------

    @Transactional
    public Map<String, Object> create(String developerId, String name, String description,
                                      String inputKind, String systemPrompt, List<Long> steps) {
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
                                      String systemPrompt, List<Long> steps, Boolean enabled) {
        PipelineEntity p = require(developerId, id);
        if (description != null) {
            p.setDescription(description);
        }
        if (systemPrompt != null) {
            p.setSystemPrompt(systemPrompt);
        }
        if (steps != null) {
            // Every step must be this developer's, and must have been probed.
            // Enabling a pipeline whose specialist has never answered moves the
            // failure to a customer's request.
            for (Long sid : steps) {
                SpecialistEntity s = specialists.require(developerId, sid);
                if (s.getStatus() == SpecialistEntity.Status.DRAFT) {
                    throw new SpecialistConnectionService.InvalidConnectionException(
                            "Specialist '" + s.getName() + "' has not been probed yet.");
                }
            }
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
        m.put("steps", stepIds(p));
        m.put("enabled", p.isEnabled());
        m.put("runs", p.getRuns());
        m.put("createdAt", p.getCreatedAt());
        return m;
    }

    private List<Long> stepIds(PipelineEntity p) {
        try {
            return mapper.readValue(p.getSteps(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, Long.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeSteps(List<Long> steps) {
        try {
            return mapper.writeValueAsString(steps == null ? List.of() : steps);
        } catch (Exception e) {
            return "[]";
        }
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
