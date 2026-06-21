package io.continuum.semantic;

import io.continuum.activities.LlmActivity;
import io.continuum.common.Json;
import io.continuum.core.event.Payloads;
import io.continuum.persistence.entity.ReplayVerificationReportEntity;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import io.continuum.persistence.repository.WorkflowEventRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantic Replay Verification Engine.
 *
 * Deterministic replay (V1) guarantees a workflow re-executes to the same
 * recorded outputs. But it cannot answer: <em>would the workflow still behave
 * correctly if run against today's models/prompts/providers?</em> This engine
 * answers that by, for each historical LLM activity, regenerating the output
 * with the current provider and scoring semantic equivalence across five
 * dimensions. It is read-only and never mutates the workflow's event log.
 */
@Service
public class SemanticReplayVerifier {

    private static final Logger log = LoggerFactory.getLogger(SemanticReplayVerifier.class);

    private final WorkflowEventRepository events;
    private final WorkflowInstanceRepository instances;
    private final ReplayVerificationReportRepository reports;
    private final SemanticComparator comparator;
    private final ProviderRouter router;
    private final Json json;

    public SemanticReplayVerifier(WorkflowEventRepository events, WorkflowInstanceRepository instances,
                                  ReplayVerificationReportRepository reports, SemanticComparator comparator,
                                  ProviderRouter router, Json json) {
        this.events = events;
        this.instances = instances;
        this.reports = reports;
        this.comparator = comparator;
        this.router = router;
        this.json = json;
    }

    @Transactional
    public ReplayVerificationReport verify(String workflowId, ReplayVerificationPolicy policy) {
        if (instances.findById(workflowId).isEmpty()) {
            throw new IllegalArgumentException("No such workflow: " + workflowId);
        }
        List<WorkflowEventEntity> history = events.findByWorkflowIdOrderBySequenceNumberAsc(workflowId);

        // Pair LLM activity inputs (from ACTIVITY_SCHEDULED) with outputs (ACTIVITY_COMPLETED).
        Map<Long, String> scheduledInputs = new HashMap<>();
        Map<Long, String> completedResults = new HashMap<>();
        for (WorkflowEventEntity e : history) {
            switch (e.getEventType()) {
                case ACTIVITY_SCHEDULED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityScheduled.class);
                    if (LlmActivity.TYPE.equals(p.activityType())) {
                        scheduledInputs.put(p.commandSeq(), p.input());
                    }
                }
                case ACTIVITY_COMPLETED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityCompleted.class);
                    if (LlmActivity.TYPE.equals(p.activityType())) {
                        completedResults.put(p.commandSeq(), p.result());
                    }
                }
                default -> {
                }
            }
        }

        List<ReplayVerificationReportEntity> items = new ArrayList<>();
        for (Map.Entry<Long, String> entry : scheduledInputs.entrySet()) {
            long seq = entry.getKey();
            String resultJson = completedResults.get(seq);
            if (resultJson == null) {
                continue; // activity never completed
            }
            LlmActivity.Input in = json.read(entry.getValue(), LlmActivity.Input.class);
            LlmActivity.Output historical = json.read(resultJson, LlmActivity.Output.class);
            if (in == null || historical == null) {
                continue;
            }

            LlmRequest request = LlmActivity.toRequest(in);
            LlmResponse fresh;
            try {
                fresh = router.complete(request);
            } catch (Exception ex) {
                log.warn("Replay verification could not regenerate seq {} of {}: {}", seq, workflowId, ex.getMessage());
                continue;
            }

            SemanticComparisonResult cmp = comparator.compare(
                    historical.content(), fresh.content(), policy);

            if (policy.useLlmJudge()) {
                cmp = augmentWithLlmJudge(historical.content(), fresh.content(), cmp, policy);
            }

            ReplayVerificationReportEntity report = new ReplayVerificationReportEntity(
                    workflowId, seq, LlmActivity.TYPE, historical.content(), fresh.content(), fresh.provider(),
                    cmp.similarityScore(), cmp.intentScore(), cmp.toolConsistencyScore(),
                    cmp.structuredCompatibilityScore(), cmp.constraintScore(), cmp.overallScore(),
                    cmp.passed(), cmp.method(), cmp.explanation());
            items.add(reports.save(report));
        }

        return aggregate(workflowId, items);
    }

    /** Optional LLM-as-judge: blend a model's equivalence rating with the lexical score. */
    private SemanticComparisonResult augmentWithLlmJudge(String historical, String fresh,
                                                         SemanticComparisonResult base,
                                                         ReplayVerificationPolicy policy) {
        try {
            String prompt = "Rate how semantically equivalent these two AI outputs are, focusing on whether "
                    + "they would lead to the SAME downstream decision. Reply with ONLY a number 0.0-1.0.\n\n"
                    + "OUTPUT A:\n" + historical + "\n\nOUTPUT B:\n" + fresh;
            LlmResponse judge = router.complete(new LlmRequest(null,
                    List.of(Message.system("You are a strict equivalence judge."), Message.user(prompt)), 16, 0.0));
            double judgeScore = parseScore(judge.content());
            if (judgeScore < 0) {
                return base;
            }
            double blended = 0.5 * base.overallScore() + 0.5 * judgeScore;
            boolean intentHardFail = base.intentScore() >= 0 && base.intentScore() < policy.intentHardFailBelow();
            boolean passed = blended >= policy.passThreshold() && !intentHardFail;
            return new SemanticComparisonResult(base.similarityScore(), base.intentScore(),
                    base.toolConsistencyScore(), base.structuredCompatibilityScore(), base.constraintScore(),
                    blended, passed, "lexical+llm-judge",
                    base.explanation() + " | judge=" + Math.round(judgeScore * 1000) / 1000.0);
        } catch (Exception e) {
            return base;
        }
    }

    private double parseScore(String content) {
        if (content == null) {
            return -1;
        }
        var m = java.util.regex.Pattern.compile("([01](?:\\.\\d+)?)").matcher(content);
        return m.find() ? Math.min(1.0, Double.parseDouble(m.group(1))) : -1;
    }

    private ReplayVerificationReport aggregate(String workflowId, List<ReplayVerificationReportEntity> items) {
        if (items.isEmpty()) {
            return new ReplayVerificationReport(workflowId, 0, 0, 0, 1.0, 0.0, 1.0, items);
        }
        double overallSum = 0, simSum = 0, intentSum = 0;
        int intentCount = 0, passed = 0;
        for (ReplayVerificationReportEntity r : items) {
            overallSum += r.getOverallScore();
            simSum += r.getSimilarityScore();
            if (r.getIntentScore() >= 0) {
                intentSum += r.getIntentScore();
                intentCount++;
            }
            if (r.isPassed()) {
                passed++;
            }
        }
        int n = items.size();
        double replayConfidence = overallSum / n;
        double semanticDrift = 1.0 - (simSum / n);
        double decisionConsistency = intentCount == 0 ? 1.0 : intentSum / intentCount;
        return new ReplayVerificationReport(workflowId, n, passed, n - passed,
                replayConfidence, semanticDrift, decisionConsistency, items);
    }

    public List<ReplayVerificationReportEntity> reportsFor(String workflowId) {
        return reports.findByWorkflowIdOrderByCommandSeqAsc(workflowId);
    }
}
