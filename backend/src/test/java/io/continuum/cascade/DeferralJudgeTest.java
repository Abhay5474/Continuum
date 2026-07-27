package io.continuum.cascade;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge decides whether a cheap answer is good enough to return. Getting it
 * wrong in one direction wastes money; wrong in the other ships worse answers
 * silently. These tests pin both edges.
 */
class DeferralJudgeTest {

    private final DeferralJudge judge = new DeferralJudge();

    private static LlmRequest ask(String prompt) {
        return new LlmRequest("m", List.of(Message.user(prompt)), 256, 0.2);
    }

    @Test
    @DisplayName("a solid, on-topic answer scores high and raises no concerns")
    void goodAnswerPasses() {
        var v = judge.judge(
                ask("What is the refund window for annual plans, and does it differ for monthly?"),
                "Annual plans have a 30-day refund window measured from the renewal date. "
                        + "Monthly plans differ: they carry a 14-day window from each billing date.",
                0.3);

        assertThat(v.clean()).isTrue();
        assertThat(v.rawScore()).isGreaterThan(0.7);
    }

    @Test
    @DisplayName("a model reporting its own failure escalates regardless of how fluent it is")
    void hedgingLanguageIsFatal() {
        var v = judge.judge(ask("What is the current order status for order 4182?"),
                "I don't know the user's order status because I do not have access to that system. "
                        + "Please contact support for assistance with your order.",
                0.4);

        assertThat(v.rawScore()).isZero();
        assertThat(v.concerns()).anyMatch(c -> c.contains("could not answer"));
    }

    @Test
    @DisplayName("an answer cut off mid-sentence is incomplete however good the start was")
    void truncationIsCaught() {
        var v = judge.judge(ask("Explain the refund policy for annual plans in detail"),
                "The refund policy for annual plans is measured from the renewal date and the window "
                        + "extends for thirty days, after which the customer may still request a partial",
                0.3);

        assertThat(v.concerns()).anyMatch(c -> c.contains("mid-sentence"));
    }

    @Test
    @DisplayName("prose does not satisfy a request for JSON")
    void formatContractIsEnforced() {
        var v = judge.judge(ask("Return the result as valid JSON with keys name and total."),
                "The customer is Acme Ltd and their total is 4,200 pounds.", 0.2);

        assertThat(v.rawScore()).isZero();
        assertThat(v.concerns()).anyMatch(c -> c.contains("JSON"));
    }

    @Test
    @DisplayName("valid JSON satisfies it, including inside a fenced block")
    void fencedJsonIsAccepted() {
        var fenced = judge.judge(ask("Return the result as valid JSON."),
                "```json\n{\"name\": \"Acme Ltd\", \"total\": 4200}\n```", 0.2);
        var bare = judge.judge(ask("Return the result as valid JSON."),
                "{\"name\": \"Acme Ltd\", \"total\": 4200}", 0.2);

        assertThat(fenced.concerns()).noneMatch(c -> c.contains("JSON"));
        assertThat(bare.concerns()).noneMatch(c -> c.contains("JSON"));
    }

    @Test
    @DisplayName("a countable instruction is counted")
    void itemCountIsChecked() {
        String prompt = "Summarise the risks in exactly 3 bullets.";
        var wrong = judge.judge(ask(prompt),
                "- risk of termination\n- risk of penalty\n- risk of audit\n- risk of delay\n- risk of churn", 0.3);
        var right = judge.judge(ask(prompt),
                "- risk of termination\n- risk of penalty\n- risk of audit", 0.3);

        assertThat(wrong.concerns()).anyMatch(c -> c.contains("3 items requested, 5 produced"));
        assertThat(right.concerns()).noneMatch(c -> c.contains("items requested"));
    }

    @Test
    @DisplayName("\"exactly N\" is violated by prose; a softer phrasing is not")
    void exactlyIsStricterThanASoftCount() {
        String prose = "The risks are termination exposure, penalty exposure and audit exposure.";
        var exact = judge.judge(ask("Summarise the risks in exactly 3 bullets."), prose, 0.3);
        var soft = judge.judge(ask("Summarise the risks in 3 bullets."), prose, 0.3);

        assertThat(exact.concerns()).anyMatch(c -> c.contains("3 items requested, 0 produced"));
        assertThat(soft.concerns()).noneMatch(c -> c.contains("items requested"));
    }

    @Test
    @DisplayName("an answer to a different question is caught by grounding")
    void offTopicIsCaught() {
        var v = judge.judge(ask("What is the refund window for annual subscription plans?"),
                "Photosynthesis converts light energy into chemical energy stored in glucose molecules.",
                0.3);

        assertThat(v.concerns()).anyMatch(c -> c.contains("shares almost nothing"));
    }

    @Test
    @DisplayName("restating the question back is caught too — the opposite failure")
    void parrotingIsCaught() {
        var v = judge.judge(ask("What is the refund window for annual subscription plans?"),
                "The refund window for annual subscription plans.", 0.3);

        assertThat(v.concerns()).anyMatch(c -> c.contains("restates the question"));
    }

    @Test
    @DisplayName("brevity is only suspicious when the question was involved")
    void substanceScalesWithComplexity() {
        String answer = "Thirty days.";
        var simple = judge.judge(ask("How long is the refund window?"), answer, 0.05);
        var complex = judge.judge(ask("How long is the refund window?"), answer, 0.9);

        assertThat(simple.rawScore()).isGreaterThan(complex.rawScore());
    }

    @Test
    @DisplayName("an empty answer is the clearest possible escalation")
    void emptyAnswer() {
        assertThat(judge.judge(ask("anything"), "", 0.5).rawScore()).isZero();
        assertThat(judge.judge(ask("anything"), null, 0.5).rawScore()).isZero();
    }
}
