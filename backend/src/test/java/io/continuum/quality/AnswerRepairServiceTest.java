package io.continuum.quality;

import io.continuum.cascade.DeferralJudge;
import io.continuum.persistence.repository.RepairAttemptRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.uncertainty.AnswerClusterer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop's contract, against the real gate.
 *
 * <p>The behaviour worth pinning is not that repair works — it is that repair
 * <em>cannot make things worse</em>. Huang et al. (ICLR 2024) showed a model
 * asked to reconsider will degrade correct work, and the only reason this is
 * safe to ship is that an independent score decides whether an attempt survives.
 */
class AnswerRepairServiceTest {

    private AnswerRepairService service;
    private QualityGate gate;

    @BeforeEach
    void setUp() {
        gate = new QualityGate(new DeferralJudge(), new AnswerClusterer());
        service = new AnswerRepairService(gate, Mockito.mock(RepairAttemptRepository.class));
    }

    private static LlmRequest request(String prompt) {
        return new LlmRequest("mock-small", List.of(Message.user(prompt)), 200, 0.2);
    }

    /** A stand-in model that returns a scripted sequence of answers. */
    private static AnswerRepairService.Regenerate scripted(List<String> answers, List<Integer> calls) {
        return messages -> {
            int i = calls.size();
            calls.add(i);
            return new AnswerRepairService.Regenerate.Attempt(
                    answers.get(Math.min(i, answers.size() - 1)), 0.0001);
        };
    }

    @Test
    @DisplayName("A repair that improves the score is kept")
    void improvementIsKept() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String bad = "Risk is important to consider carefully in this situation.";
        String good = "- Market risk is elevated\n- Credit exposure is concentrated\n"
                + "- Liquidity cover is thin";

        var verdict = gate.check(request(prompt), bad, 0.2, 0.75);
        assertThat(verdict.action()).isEqualTo(QualityGate.Action.REPAIR);

        List<Integer> calls = new ArrayList<>();
        var r = service.repair("dev", "mock-small", request(prompt), bad, verdict, 0.2, 0.75,
                2, 10_000, scripted(List.of(good), calls));

        assertThat(r.improved()).isTrue();
        assertThat(r.answer()).isEqualTo(good);
        assertThat(r.finalScore()).isGreaterThan(r.originalScore());
        assertThat(r.attempts()).hasSize(1);
        assertThat(r.attempts().get(0)).containsEntry("kept", true);
    }

    @Test
    @DisplayName("A repair that scores no better is discarded and the original stands")
    void regressionIsDiscarded() {
        // The guard. Without it this is a coin flip that costs money.
        String prompt = "Reply with exactly 3 bullet points about risk.";
        // A real, named defect — the wrong number of items — so there is
        // something to repair.
        String original = "Risk is worth thinking about here.";
        // And a "repair" that is worse than what it replaced.
        String worse = "I am not able to help with that.";

        var verdict = gate.check(request(prompt), original, 0.2, 0.75);
        assertThat(verdict.defects()).isNotEmpty();

        List<Integer> calls = new ArrayList<>();
        var r = service.repair("dev", "mock-small", request(prompt), original, verdict, 0.2, 0.75,
                2, 10_000, scripted(List.of(worse), calls));

        assertThat(r.answer()).isEqualTo(original);
        assertThat(r.improved()).isFalse();
        assertThat(r.attempts().get(0)).containsEntry("kept", false);
        assertThat(String.valueOf(r.attempts().get(0).get("note"))).contains("discarded");
    }

    @Test
    @DisplayName("A refusal is never sent back to the model")
    void refusalIsNotRepaired() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String refusal = "I'm sorry, but I can't help with that.";
        var verdict = gate.check(request(prompt), refusal, 0.2, 0.75);

        List<Integer> calls = new ArrayList<>();
        var r = service.repair("dev", "mock-small", request(prompt), refusal, verdict, 0.2, 0.75,
                2, 10_000, scripted(List.of("anything"), calls));

        // Not one model call: asking again is how you get the same refusal
        // twice and pay for both.
        assertThat(calls).isEmpty();
        assertThat(r.answer()).isEqualTo(refusal);
        assertThat(r.attempts()).hasSize(1);
        assertThat(r.attempts().get(0)).containsEntry("kept", false);
    }

    @Test
    @DisplayName("The loop stops as soon as the answer passes")
    void stopsOnSuccess() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String bad = "Risk matters.";
        String good = "- Market risk is elevated\n- Credit exposure is concentrated\n"
                + "- Liquidity cover is thin";

        var verdict = gate.check(request(prompt), bad, 0.2, 0.6);
        List<Integer> calls = new ArrayList<>();
        service.repair("dev", "mock-small", request(prompt), bad, verdict, 0.2, 0.6,
                3, 10_000, scripted(List.of(good, good, good), calls));

        // An easy fix costs one call, not the whole budget.
        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("A failed repair call leaves the original answer intact")
    void modelFailureIsNotFatal() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String original = "Risk matters.";
        var verdict = gate.check(request(prompt), original, 0.2, 0.75);

        var r = service.repair("dev", "mock-small", request(prompt), original, verdict, 0.2, 0.75,
                2, 10_000, messages -> {
                    throw new RuntimeException("provider exploded");
                });

        // The answer already exists; discarding it because the fix did not
        // arrive would be the wrong trade every time.
        assertThat(r.answer()).isEqualTo(original);
        assertThat(r.improved()).isFalse();
        assertThat(String.valueOf(r.attempts().get(0).get("note"))).contains("failed");
    }

    @Test
    @DisplayName("Zero attempts configured means no model call at all")
    void zeroAttemptsDoesNothing() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String original = "Risk matters.";
        var verdict = gate.check(request(prompt), original, 0.2, 0.75);

        List<Integer> calls = new ArrayList<>();
        var r = service.repair("dev", "mock-small", request(prompt), original, verdict, 0.2, 0.75,
                0, 10_000, scripted(List.of("x"), calls));

        assertThat(calls).isEmpty();
        assertThat(r.answer()).isEqualTo(original);
    }

    @Test
    @DisplayName("An exhausted budget stops the loop before spending anything")
    void budgetIsRespected() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String original = "Risk matters.";
        var verdict = gate.check(request(prompt), original, 0.2, 0.75);

        List<Integer> calls = new ArrayList<>();
        var r = service.repair("dev", "mock-small", request(prompt), original, verdict, 0.2, 0.75,
                2, 0, scripted(List.of("x"), calls));

        assertThat(calls).isEmpty();
        assertThat(String.valueOf(r.attempts().get(0).get("note"))).contains("budget");
    }

    @Test
    @DisplayName("Every attempt is reported, kept or not")
    void attemptsAreAlwaysRecorded() {
        String prompt = "Reply with exactly 3 bullet points about risk.";
        String original = "Risk matters.";
        var verdict = gate.check(request(prompt), original, 0.2, 0.75);

        List<Integer> calls = new ArrayList<>();
        var r = service.repair("dev", "mock-small", request(prompt), original, verdict, 0.2, 0.75,
                1, 10_000, scripted(List.of("still not bullets"), calls));

        // A repair engine whose failures are invisible cannot be evaluated.
        assertThat(r.attempts()).isNotEmpty();
        assertThat(r.attempts().get(0)).containsKeys(
                "attempt", "strategy", "defects", "instruction", "scoreBefore", "scoreAfter",
                "kept", "note", "cost", "latencyMs");
    }
}
