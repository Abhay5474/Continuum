package io.continuum.mmu;

import io.continuum.provider.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case worth testing is the one recency gets wrong: something said long ago
 * that the current question is entirely about.
 */
class WorkingSetTest {

    /** Rough token estimate, matching the MMU's own granularity closely enough. */
    private static int tokens(Message m) {
        return Math.max(1, m.content().length() / 4);
    }

    private static List<WorkingSet.Scored> select(List<Message> msgs, String task, int budget) {
        return WorkingSet.select(msgs, task, budget, WorkingSetTest::tokens);
    }

    @Test
    @DisplayName("An old message the question is about survives; newer chatter does not")
    void relevanceBeatsRecency() {
        // The failure recency has: the order number arrives early and is asked
        // about late, and LRU pages out the answer to keep the small talk.
        List<Message> history = List.of(
                Message.user("My order number is ZX-4419 and it shipped on Tuesday."),
                Message.assistant("Thanks, noted."),
                Message.user("Lovely weather we are having lately."),
                Message.assistant("It certainly is very pleasant today."));

        var out = select(history, "What is the status of order ZX-4419?", 20);

        assertThat(out.get(0).resident())
                .as("the message containing the order number must stay resident")
                .isTrue();
        assertThat(out.get(0).relevance()).isGreaterThan(out.get(2).relevance());
    }

    @Test
    @DisplayName("With nothing relevant, recency decides")
    void recencyIsTheTiebreak() {
        List<Message> history = List.of(
                Message.user("aaa bbb ccc"),
                Message.user("ddd eee fff"),
                Message.user("ggg hhh iii"));

        var out = select(history, "completely unrelated question about zebras", 4);

        // No relevance signal anywhere, so the later message wins — usually the
        // one that superseded the earlier.
        assertThat(out.get(2).score()).isGreaterThan(out.get(0).score());
    }

    @Test
    @DisplayName("The budget is respected, and the best fit is bought first")
    void budgetIsSpentOnTheBestFirst() {
        List<Message> history = List.of(
                Message.user("irrelevant padding about gardening and compost heaps"),
                Message.user("the refund policy is thirty days from delivery"),
                Message.user("more irrelevant padding about bicycle maintenance"));

        var out = select(history, "what is the refund policy?", 12);

        assertThat(out.get(1).resident()).isTrue();
        long resident = out.stream().filter(WorkingSet.Scored::resident).count();
        assertThat(resident).isLessThan(3);
    }

    @Test
    @DisplayName("Conversation order is preserved, not score order")
    void orderIsPreserved() {
        // A conversation reordered by relevance is not a conversation.
        List<Message> history = List.of(
                Message.user("first"), Message.user("second"), Message.user("third"));

        var out = select(history, "third", 100);

        assertThat(out).extracting(s -> s.message().content())
                .containsExactly("first", "second", "third");
        assertThat(out).extracting(WorkingSet.Scored::index).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("A zero budget keeps nothing resident rather than overflowing")
    void zeroBudgetKeepsNothing() {
        var out = select(List.of(Message.user("anything at all")), "anything", 0);

        assertThat(out).allMatch(s -> !s.resident());
    }

    @Test
    @DisplayName("A generous budget keeps everything")
    void generousBudgetKeepsEverything() {
        var out = select(List.of(Message.user("one"), Message.user("two")), "one", 10_000);

        assertThat(out).allMatch(WorkingSet.Scored::resident);
    }

    @Test
    @DisplayName("No task means no relevance signal, never a crash")
    void missingTaskIsHandled() {
        assertThat(WorkingSet.relevance(Message.user("hello"), null)).isZero();
        assertThat(WorkingSet.relevance(Message.user("hello"), "  ")).isZero();
        assertThat(WorkingSet.relevance(Message.user(""), "hello")).isZero();
    }

    @Test
    @DisplayName("Relevance stays inside 0–1 so the weights mean what they say")
    void relevanceIsBounded() {
        double r = WorkingSet.relevance(
                Message.user("the refund policy is thirty days"), "refund policy");

        assertThat(r).isBetween(0.0, 1.0);
        assertThat(r).isGreaterThan(0);
    }

    @Test
    @DisplayName("Every candidate is reported, resident or not")
    void everyCandidateIsExplained() {
        // A page-out nobody can account for is indistinguishable from a bug.
        var out = select(List.of(Message.user("a"), Message.user("b")), "a", 1);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).describe())
                .containsKeys("index", "relevance", "recency", "score", "resident");
    }
}
