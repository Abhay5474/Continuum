package io.continuum.degradation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The risk in this feature is not that it fails to degrade — it is that it
 * degrades quietly. A stale answer indistinguishable from a fresh one is worse
 * than the 502 it replaced.
 */
class DegradationLadderTest {

    @Test
    @DisplayName("A cached answer is preferred to nothing, and says it may be stale")
    void cachedRungIsUsedAndDisclosed() {
        var out = DegradationLadder.descend("The office closes at 5pm.", "connection refused");

        assertThat(out.rung()).isEqualTo(DegradationLadder.Rung.CACHED);
        assertThat(out.answer()).isEqualTo("The office closes at 5pm.");
        assertThat(out.reason()).contains("may be out of date");
    }

    @Test
    @DisplayName("With nothing cached, the bottom rung says so plainly")
    void staticRungIsHonest() {
        var out = DegradationLadder.descend(null, "all providers down");

        assertThat(out.rung()).isEqualTo(DegradationLadder.Rung.STATIC);
        assertThat(out.answer()).contains("No answer could be produced");
        // Not an apology-shaped nothing: it says this is a service problem
        // rather than something wrong with the question.
        assertThat(out.answer()).contains("not a judgement about the question");
    }

    @Test
    @DisplayName("Every degraded outcome is marked degraded")
    void degradationIsAlwaysVisible() {
        // The failure mode this feature could most easily become is silently
        // succeeding.
        assertThat(DegradationLadder.descend("x", "y").describe()).containsEntry("degraded", true);
        assertThat(DegradationLadder.descend(null, "y").describe()).containsEntry("degraded", true);
        assertThat(DegradationLadder.Rung.FULL.isDegraded()).isFalse();
    }

    @Test
    @DisplayName("A blank cached answer is not treated as a cache hit")
    void blankCacheIsNotAnAnswer() {
        assertThat(DegradationLadder.descend("", "boom").rung())
                .isEqualTo(DegradationLadder.Rung.STATIC);
        assertThat(DegradationLadder.descend("   ", "boom").rung())
                .isEqualTo(DegradationLadder.Rung.STATIC);
    }

    @Test
    @DisplayName("The upstream cause is shortened, not forwarded whole")
    void upstreamDetailIsNotLeaked() {
        // Provider errors carry stack detail and sometimes credential-adjacent
        // text. The caller gets a cause, not an upstream's internals.
        String noisy = "HTTP 401 Unauthorized for key sk-abcdef\n  at com.provider.Client.call\n"
                + "  at com.provider.Retry.run";
        String reason = DegradationLadder.descend(null, noisy).reason();

        assertThat(reason).doesNotContain("at com.provider");
        assertThat(reason.length()).isLessThan(300);
    }

    @Test
    @DisplayName("A missing cause still produces a usable reason")
    void missingCauseIsHandled() {
        assertThat(DegradationLadder.descend(null, null).reason()).contains("no cause reported");
    }
}
