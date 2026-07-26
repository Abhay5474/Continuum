package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.common.Json;
import io.continuum.core.event.Payloads;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.repository.WorkflowEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies that a declarative run would replay to the same decisions.
 *
 * <p>For a workflow made of HTTP calls, "verify the replay" cannot mean calling
 * the endpoints again — re-running a charge to check it still charges is not a
 * verification, it is a second charge. So this checks the property that actually
 * underwrites crash recovery: <em>given the same recorded history, does the
 * engine decide the same things?</em>
 *
 * <p>It re-interprets the run's pinned spec against the values already in the
 * history and compares, step by step, what would be scheduled against what was
 * scheduled: the same steps, in the same order, with the same resolved URL,
 * method, body and idempotency key, and the same guards passing or skipping.
 *
 * <p>A divergence here is precisely the bug class that breaks durability — a
 * template that resolves differently, a guard that consults something outside
 * the recorded scope, a spec edited under an in-flight run. Because it reads
 * only the event log and performs no I/O, it is safe to run against production
 * history at any time.
 */
@Service
public class DeterministicReplayVerifier {

    private static final Logger log = LoggerFactory.getLogger(DeterministicReplayVerifier.class);

    private final WorkflowEventRepository events;
    private final ObjectMapper mapper;
    private final Json json;

    public DeterministicReplayVerifier(WorkflowEventRepository events, ObjectMapper mapper, Json json) {
        this.events = events;
        this.mapper = mapper;
        this.json = json;
    }

    /** One step's verdict. {@code expected}/{@code recorded} are only set on divergence. */
    public record StepCheck(String stepId, long commandSeq, String kind, boolean matched,
                            String detail, String expected, String recorded) {
    }

    /**
     * @param applicable false when the workflow is not declarative, so the caller
     *                   can say "not checked" rather than imply a pass
     */
    public record Result(boolean applicable, int checked, int matched, int diverged,
                         List<StepCheck> checks) {

        public static Result notApplicable() {
            return new Result(false, 0, 0, 0, List.of());
        }
    }

    /** Replays {@code workflowId}'s decisions against its own history. */
    public Result verify(String workflowId, String workflowType, String inputJson) {
        if (!DeclarativeWorkflow.TYPE.equals(workflowType) || inputJson == null) {
            return Result.notApplicable();
        }

        DeclarativeWorkflow.Run run;
        WorkflowSpec spec;
        try {
            run = json.read(inputJson, DeclarativeWorkflow.Run.class);
            spec = mapper.convertValue(run.spec(), WorkflowSpec.class);
        } catch (Exception e) {
            log.warn("Replay verification could not read the pinned spec for {}: {}", workflowId, e.getMessage());
            return Result.notApplicable();
        }

        Recorded recorded = readHistory(workflowId);
        List<StepCheck> checks = new ArrayList<>();

        // Rebuild the scope exactly as the workflow did, from recorded results only.
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", run.input() == null ? Map.of() : run.input());
        Map<String, Object> stepResults = new LinkedHashMap<>();
        scope.put("steps", stepResults);

        int cursor = 0;
        for (List<WorkflowSpec.Step> layer : spec.topologicalLayers()) {
            List<WorkflowSpec.Step> live = new ArrayList<>();
            for (WorkflowSpec.Step s : layer) {
                boolean guardPasses = s.getCondition() == null || s.getCondition().isBlank()
                        || Conditions.evaluate(s.getCondition(), scope);
                if (guardPasses) {
                    live.add(s);
                } else {
                    // A skipped step should have scheduled nothing. If the history
                    // shows a call for it, the guard is not deterministic.
                    boolean wronglyScheduled = recorded.scheduledStepIds.contains(s.getId());
                    checks.add(new StepCheck(s.getId(), -1, "guard", !wronglyScheduled,
                            wronglyScheduled
                                    ? "guard is false on replay but the step was executed originally"
                                    : "guard false — correctly skipped, as recorded",
                            null, null));
                    stepResults.put(s.getId(), Map.of("skipped", true));
                }
            }

            for (WorkflowSpec.Step s : live) {
                if (cursor >= recorded.scheduled.size()) {
                    checks.add(new StepCheck(s.getId(), -1, kind(s), false,
                            "replay expects this step but the history has no scheduling for it",
                            describe(s, scope, workflowId), null));
                    continue;
                }
                Scheduled actual = recorded.scheduled.get(cursor++);
                String expected = describe(s, scope, workflowId);
                boolean same = Objects.equals(expected, actual.input());
                checks.add(new StepCheck(s.getId(), actual.commandSeq(), kind(s), same,
                        same ? "identical to the recorded call" : "replay would issue a different call",
                        same ? null : expected, same ? null : actual.input()));

                // Feed the recorded result forward, never a fresh one — the point is
                // to check the decisions, not to re-derive the answers.
                Object body = actual.resultBody();
                stepResults.put(s.getId(), body instanceof Map ? body : Map.of("value", String.valueOf(body)));
            }
        }

        // The completion callback is a durable step like any other, so it is held
        // to the same standard: same URL, same idempotency key.
        if (spec.getOnComplete() != null && cursor < recorded.scheduled.size()) {
            Scheduled actual = recorded.scheduled.get(cursor);
            String expectedUrl = String.valueOf(Templates.resolve(spec.getOnComplete().getUrl(), scope));
            String expectedKey = workflowId + ":onComplete";
            boolean same = actual.input() != null
                    && actual.input().contains("\"" + expectedKey + "\"")
                    && actual.input().contains(expectedUrl);
            checks.add(new StepCheck("onComplete", actual.commandSeq(), "callback", same,
                    same ? "callback target and key unchanged" : "callback would be delivered differently",
                    same ? null : expectedUrl + " / " + expectedKey,
                    same ? null : actual.input()));
        }

        int matched = (int) checks.stream().filter(StepCheck::matched).count();
        return new Result(true, checks.size(), matched, checks.size() - matched, checks);
    }

    private static String kind(WorkflowSpec.Step s) {
        return s.getType() == WorkflowSpec.Kind.WAIT ? "wait" : "http";
    }

    /** The activity input this step would produce now, serialised for comparison. */
    private String describe(WorkflowSpec.Step s, Map<String, Object> scope, String workflowId) {
        try {
            if (s.getType() == WorkflowSpec.Kind.WAIT) {
                int seconds = s.getWaitSeconds() == null ? 1 : s.getWaitSeconds();
                return json.write(new WaitStepActivity.Input(s.getId(), seconds));
            }
            WorkflowSpec.Call call = s.getCall();
            Map<String, String> headers = new LinkedHashMap<>();
            call.getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(Templates.resolve(v, scope))));
            return json.write(new HttpStepActivity.Input(
                    String.valueOf(Templates.resolve(call.getUrl(), scope)),
                    call.getMethod(), headers, Templates.resolve(call.getBody(), scope),
                    s.getTimeoutSeconds(), workflowId + ":" + s.getId()));
        } catch (Exception e) {
            return "<unresolvable: " + e.getMessage() + ">";
        }
    }

    private record Scheduled(long commandSeq, String activityType, String input, Object resultBody) {
    }

    private record Recorded(List<Scheduled> scheduled, java.util.Set<String> scheduledStepIds) {
    }

    /** Pulls the scheduled step calls and their results out of the event log, in order. */
    private Recorded readHistory(String workflowId) {
        List<WorkflowEventEntity> history = events.findByWorkflowIdOrderBySequenceNumberAsc(workflowId);

        Map<Long, String> inputs = new LinkedHashMap<>();
        Map<Long, String> types = new LinkedHashMap<>();
        Map<Long, Object> results = new LinkedHashMap<>();
        for (WorkflowEventEntity e : history) {
            switch (e.getEventType()) {
                case ACTIVITY_SCHEDULED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityScheduled.class);
                    if (isDeclarative(p.activityType())) {
                        inputs.put(p.commandSeq(), p.input());
                        types.put(p.commandSeq(), p.activityType());
                    }
                }
                case ACTIVITY_COMPLETED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityCompleted.class);
                    if (isDeclarative(p.activityType())) {
                        results.put(p.commandSeq(), bodyOf(p.result()));
                    }
                }
                default -> {
                }
            }
        }

        List<Scheduled> scheduled = new ArrayList<>();
        java.util.Set<String> stepIds = new java.util.HashSet<>();
        inputs.keySet().stream().sorted().forEach(seq -> {
            scheduled.add(new Scheduled(seq, types.get(seq), inputs.get(seq), results.get(seq)));
            stepIds.add(stepIdOf(inputs.get(seq)));
        });
        return new Recorded(scheduled, stepIds);
    }

    private static boolean isDeclarative(String activityType) {
        return HttpStepActivity.TYPE.equals(activityType) || WaitStepActivity.TYPE.equals(activityType);
    }

    /** The step name a declarative call signed itself with. */
    private String stepIdOf(String inputJson) {
        try {
            Map<?, ?> m = mapper.readValue(inputJson, Map.class);
            Object key = m.get("idempotencyKey");
            if (key == null) {
                key = m.get("stepId");
            }
            String s = String.valueOf(key);
            return s.contains(":") ? s.substring(s.lastIndexOf(':') + 1) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private Object bodyOf(String resultJson) {
        try {
            Map<?, ?> m = mapper.readValue(resultJson, Map.class);
            return m.get("body");
        } catch (Exception e) {
            return null;
        }
    }
}
