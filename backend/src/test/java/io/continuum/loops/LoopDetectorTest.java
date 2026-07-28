package io.continuum.loops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hard half is not spotting a loop — it is not calling one on an agent that
 * is working. Legitimate work repeats constantly, and a detector that fires on
 * repetition alone would stop more real work than runaway loops.
 */
class LoopDetectorTest {

    private static List<Boolean> noProgress(int n) {
        return java.util.Collections.nCopies(n, false);
    }

    @Test
    @DisplayName("The same step three times with nothing learned is a loop")
    void exactRepetition() {
        var v = LoopDetector.inspect(
                List.of("read config.yaml", "read config.yaml", "read config.yaml"),
                noProgress(3));

        assertThat(v.looping()).isTrue();
        assertThat(v.kind()).isEqualTo(LoopDetector.Kind.REPETITION);
        assertThat(v.reason()).contains("nothing new learned");
    }

    @Test
    @DisplayName("Near-identical rewording is caught, which string equality cannot do")
    void paraphrasedRepetition() {
        // Word order changes, nothing else. This is what the threshold is set to
        // catch; broader paraphrase is out of reach and the class says so.
        var v = LoopDetector.inspect(List.of(
                "I will check the database connection settings now",
                "Now I will check the settings for the database connection",
                "The database connection settings I will now check"), noProgress(3));

        assertThat(v.looping()).isTrue();
        assertThat(v.kind()).isEqualTo(LoopDetector.Kind.PARAPHRASE);
    }

    @Test
    @DisplayName("Iterating over files is not a loop, though the prose is identical")
    void differentArgumentsAreDifferentSteps() {
        // Measured: "read file src/a.java" and "read file src/b.java" score
        // 1.000 on this codebase's similarity, because the vectoriser drops the
        // filenames. Iterating over files is the most common legitimate
        // repetition there is — firing here would be the worst false positive
        // available.
        var v = LoopDetector.inspect(List.of(
                "read file src/a.java", "read file src/b.java", "read file src/c.java"),
                noProgress(3));

        assertThat(v.looping()).isFalse();
    }

    @Test
    @DisplayName("Numbers and quoted arguments count as arguments too")
    void argumentsAreRecognisedBroadly() {
        assertThat(LoopDetector.sameArguments("fetch page 1", "fetch page 2")).isFalse();
        assertThat(LoopDetector.sameArguments("query \"orders\"", "query \"customers\"")).isFalse();
        assertThat(LoopDetector.sameArguments("open a/b.txt", "open a/b.txt")).isTrue();
        // No arguments on either side is not a difference.
        assertThat(LoopDetector.sameArguments("try again", "try once more")).isTrue();
    }

    @Test
    @DisplayName("The same file over and over IS a loop")
    void sameArgumentRepeatedStillLoops() {
        var v = LoopDetector.inspect(List.of(
                "read file src/a.java", "read file src/a.java", "read file src/a.java"),
                noProgress(3));

        assertThat(v.looping()).isTrue();
    }

    @Test
    @DisplayName("A→B→A→B is caught, though neither step ever repeats consecutively")
    void oscillation() {
        // The shape that runs longest, because "same as last time" never fires.
        var v = LoopDetector.inspect(List.of(
                "open the configuration file",
                "search the logs for errors",
                "open the configuration file",
                "search the logs for errors",
                "open the configuration file",
                "search the logs for errors"), noProgress(6));

        assertThat(v.looping()).isTrue();
        assertThat(v.kind()).isEqualTo(LoopDetector.Kind.OSCILLATION);
        assertThat(v.evidence()).hasSize(2);
    }

    // --- the half that matters --------------------------------------------------

    @Test
    @DisplayName("Repetition with progress is work, not a loop")
    void progressVetoesTheVerdict() {
        // A loop over twenty files issues twenty near-identical steps and is not
        // stuck. Firing here would stop more real work than runaway loops.
        var v = LoopDetector.inspect(
                List.of("read the file", "read the file", "read the file"),
                List.of(true, true, true));

        assertThat(v.looping()).isFalse();
    }

    @Test
    @DisplayName("Progress on any recent step clears it, not only the latest")
    void recentProgressIsEnough() {
        var v = LoopDetector.inspect(
                List.of("check status", "check status", "check status", "check status"),
                List.of(false, true, false, false));

        assertThat(v.looping()).isFalse();
    }

    @Test
    @DisplayName("Different steps are never a loop")
    void distinctWorkIsNotALoop() {
        var v = LoopDetector.inspect(List.of(
                "read the schema", "write the migration", "run the tests",
                "update the changelog"), noProgress(4));

        assertThat(v.looping()).isFalse();
    }

    @Test
    @DisplayName("Two repeats is a retry, not yet a loop")
    void oneRetryIsAllowed() {
        // Retrying once is normal behaviour, and calling it a loop would make
        // the detector fire on ordinary transient failures.
        var v = LoopDetector.inspect(
                List.of("call the API", "call the API"), noProgress(2));

        assertThat(v.looping()).isFalse();
    }

    @Test
    @DisplayName("Nothing is judged before there is enough history")
    void tooEarlyToJudge() {
        assertThat(LoopDetector.inspect(List.of("a"), noProgress(1)).looping()).isFalse();
        assertThat(LoopDetector.inspect(List.of(), List.of()).looping()).isFalse();
        assertThat(LoopDetector.inspect(null, null).looping()).isFalse();
    }

    @Test
    @DisplayName("Unknown progress is treated as none")
    void unknownProgressIsNotAnExcuse() {
        // An agent that cannot say whether it advanced is exactly the one worth
        // watching, so silence must not read as "making progress".
        var v = LoopDetector.inspect(
                List.of("retry the request", "retry the request", "retry the request"), null);

        assertThat(v.looping()).isTrue();
    }

    @Test
    @DisplayName("Every verdict names the steps it is accusing")
    void verdictCarriesEvidence() {
        // "Your agent is looping" without the steps is unactionable.
        var v = LoopDetector.inspect(
                List.of("ping the host", "ping the host", "ping the host"), noProgress(3));

        assertThat(v.evidence()).isNotEmpty();
        assertThat(v.describe()).containsKeys("kind", "looping", "at", "confidence", "reason", "evidence");
    }
}
