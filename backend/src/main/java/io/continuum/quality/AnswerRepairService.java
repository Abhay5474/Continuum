package io.continuum.quality;

import io.continuum.persistence.entity.RepairAttemptEntity;
import io.continuum.persistence.repository.RepairAttemptRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Diagnoses why an answer is deficient and fixes that specific defect.
 *
 * <p>The single-shot repair sends every defect at once and keeps whatever comes
 * back. This does three things differently, and the third is the one that
 * matters.
 *
 * <ol>
 *   <li><b>One defect kind per attempt.</b> Asking a model to fix four things at
 *       once is how the two that were already right get rewritten.</li>
 *   <li><b>Re-check after every attempt.</b> The loop stops the moment the
 *       answer passes, so an easy fix costs one call and not the budget.</li>
 *   <li><b>The answer never gets worse.</b> Every attempt is scored by the same
 *       external gate, and an attempt that scores lower than what it replaced is
 *       <em>discarded</em>.</li>
 * </ol>
 *
 * <p>That last point is the whole reason this is safe to ship. Huang et al.,
 * <i>Large Language Models Cannot Self-Correct Reasoning Yet</i> (ICLR 2024),
 * found that a model asked to reconsider will find fault with correct work and
 * degrade it. Continuum is not relying on the model's judgement of its own
 * answer — it has an independent score, so it can simply check whether the
 * repair helped and throw it away when it did not. <b>Repair without that check
 * is a coin flip that costs money.</b>
 *
 * <p>Every attempt is recorded: the defect, the strategy, the score before and
 * after, and whether it was kept. Repair that cannot be inspected is repair
 * nobody can tell was working.
 */
@Service
public class AnswerRepairService {

    private static final Logger log = LoggerFactory.getLogger(AnswerRepairService.class);

    /** Hard ceiling regardless of configuration. */
    private static final int MAX_ATTEMPTS = 3;

    private final QualityGate gate;
    private final RepairAttemptRepository repo;

    public AnswerRepairService(QualityGate gate, RepairAttemptRepository repo) {
        this.gate = gate;
        this.repo = repo;
    }

    /**
     * @param answer      the answer to return — the best one seen
     * @param improved    whether any attempt actually raised the score
     * @param attempts    what was tried, in order
     * @param finalScore  the score of {@link #answer}
     */
    public record Result(String answer, double finalScore, double originalScore, boolean improved,
                         List<Map<String, Object>> attempts, long totalMs, double totalCost) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("originalScore", Math.round(originalScore * 1000) / 1000.0);
            m.put("finalScore", Math.round(finalScore * 1000) / 1000.0);
            m.put("improved", improved);
            m.put("attempts", attempts);
            m.put("totalMs", totalMs);
            m.put("totalCost", totalCost);
            return m;
        }
    }

    /** One call to a model, so the loop does not need to know about providers. */
    public interface Regenerate {
        /** @return the new answer, and what it cost */
        Attempt call(List<Message> messages);

        record Attempt(String answer, double cost) {
        }
    }

    /**
     * Repairs until the answer passes, the budget runs out, or nothing more can
     * usefully be asked.
     *
     * @param maxAttempts caller's cap, itself capped at {@link #MAX_ATTEMPTS}
     * @param budgetMs    total wall-clock budget across every attempt
     */
    @Transactional
    public Result repair(String developerId, String model, LlmRequest canonical, String original,
                         QualityGate.Verdict verdict, double complexity, double threshold,
                         int maxAttempts, long budgetMs, Regenerate regenerate) {

        long started = System.nanoTime();
        List<Map<String, Object>> attempts = new ArrayList<>();
        double totalCost = 0;

        String best = original;
        double bestScore = verdict.score();
        double originalScore = verdict.score();
        QualityGate.Verdict current = verdict;

        int cap = Math.max(0, Math.min(MAX_ATTEMPTS, maxAttempts));
        for (int i = 0; i < cap; i++) {
            RepairStrategy.Plan plan = RepairStrategy.plan(current.defects());
            if (!plan.repairable()) {
                attempts.add(record(developerId, model, i + 1, plan, bestScore, bestScore,
                        false, "nothing repairable — " + describeStrategies(plan), 0, 0));
                break;
            }

            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (elapsed >= budgetMs) {
                attempts.add(record(developerId, model, i + 1, plan, bestScore, bestScore,
                        false, "budget exhausted before the attempt", 0, 0));
                break;
            }

            List<Message> messages = new ArrayList<>(canonical.messages());
            messages.add(Message.assistant(best));
            messages.add(Message.user(plan.instruction()));

            long attemptStart = System.nanoTime();
            Regenerate.Attempt out;
            try {
                out = regenerate.call(messages);
            } catch (RuntimeException e) {
                // A failed repair is not a failed request. The answer already
                // exists; discarding it because the fix did not arrive would be
                // the wrong trade every time.
                log.warn("Repair attempt failed for {}: {}", developerId, e.getMessage());
                attempts.add(record(developerId, model, i + 1, plan, bestScore, bestScore,
                        false, "the repair call failed", 0,
                        (System.nanoTime() - attemptStart) / 1_000_000));
                break;
            }
            long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
            totalCost += out.cost();

            QualityGate.Verdict after = gate.check(canonical, out.answer(), complexity, threshold);

            // The guard. An independent score decides, not the model's opinion
            // of its own work — which is exactly what Huang et al. showed cannot
            // be trusted.
            boolean kept = after.score() > bestScore;
            String note;
            if (kept) {
                note = "kept — score rose from " + round(bestScore) + " to " + round(after.score());
                best = out.answer();
                bestScore = after.score();
                current = after;
            } else {
                note = "discarded — score would have "
                        + (after.score() < bestScore ? "fallen to " : "stayed at ")
                        + round(after.score());
            }
            attempts.add(record(developerId, model, i + 1, plan, bestScore, after.score(),
                    kept, note, out.cost(), attemptMs));

            if (kept && after.passed()) {
                break;
            }
            if (!kept) {
                // The targeted instruction did not help. Trying the same defect
                // again with the same instruction would produce the same result
                // and bill for it.
                break;
            }
        }

        long totalMs = (System.nanoTime() - started) / 1_000_000;
        return new Result(best, bestScore, originalScore, bestScore > originalScore,
                attempts, totalMs, totalCost);
    }

    private Map<String, Object> record(String developerId, String model, int attempt,
                                       RepairStrategy.Plan plan, double before, double after,
                                       boolean kept, String note, double cost, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", attempt);
        m.put("strategy", plan.strategies().isEmpty() ? "NONE"
                : plan.strategies().get(0).name());
        m.put("allStrategies", plan.strategies().stream().map(Enum::name).toList());
        m.put("defects", plan.defects());
        m.put("instruction", plan.instruction());
        m.put("scoreBefore", round(before));
        m.put("scoreAfter", round(after));
        m.put("kept", kept);
        m.put("note", note);
        m.put("cost", cost);
        m.put("latencyMs", ms);

        try {
            repo.save(new RepairAttemptEntity(developerId, model, attempt,
                    plan.strategies().isEmpty() ? "NONE" : plan.strategies().get(0).name(),
                    String.join("; ", plan.defects()), before, after, kept, note, cost, ms));
        } catch (RuntimeException e) {
            // Losing the record must never lose the repair.
            log.debug("Could not record repair attempt: {}", e.getMessage());
        }
        return m;
    }

    private static String describeStrategies(RepairStrategy.Plan plan) {
        return plan.strategies().isEmpty() ? "no defect kind identified"
                : plan.strategies().get(0).label();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // --- console --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String developerId, int limit) {
        return repo.findByDeveloperIdOrderByCreatedAtDesc(developerId,
                        PageRequest.of(0, Math.max(1, Math.min(200, limit))))
                .stream().map(AnswerRepairService::describe).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String developerId) {
        List<RepairAttemptEntity> all = repo.findByDeveloperIdOrderByCreatedAtDesc(
                developerId, PageRequest.of(0, 500));
        long kept = all.stream().filter(RepairAttemptEntity::isKept).count();
        double gained = all.stream().filter(RepairAttemptEntity::isKept)
                .mapToDouble(a -> a.getScoreAfter() - a.getScoreBefore()).sum();
        Map<String, Long> byStrategy = new LinkedHashMap<>();
        for (RepairAttemptEntity a : all) {
            byStrategy.merge(a.getStrategy(), 1L, Long::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempts", all.size());
        m.put("kept", kept);
        m.put("discarded", all.size() - kept);
        m.put("totalScoreGained", Math.round(gained * 1000) / 1000.0);
        m.put("byStrategy", byStrategy);
        m.put("cost", Math.round(all.stream().mapToDouble(RepairAttemptEntity::getCost).sum() * 1e6) / 1e6);
        return m;
    }

    @Transactional
    public void clear(String developerId) {
        repo.deleteByDeveloperId(developerId);
    }

    private static Map<String, Object> describe(RepairAttemptEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("model", a.getModel());
        m.put("attempt", a.getAttempt());
        m.put("strategy", a.getStrategy());
        m.put("defects", a.getDefects());
        m.put("scoreBefore", a.getScoreBefore());
        m.put("scoreAfter", a.getScoreAfter());
        m.put("kept", a.isKept());
        m.put("note", a.getNote());
        m.put("cost", a.getCost());
        m.put("latencyMs", a.getLatencyMs());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }
}
