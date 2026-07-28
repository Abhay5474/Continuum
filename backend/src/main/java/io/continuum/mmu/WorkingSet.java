package io.continuum.mmu;

import io.continuum.provider.model.Message;
import io.continuum.semantic.TextVectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which parts of a long conversation the current request actually needs.
 *
 * <p>Eviction was positional: oldest first, newest kept. That is LRU, and LRU is
 * the wrong policy here for the reason Denning identified in 1968 — what matters
 * is not what was touched most recently but what belongs to the <b>working
 * set</b> of the task in front of you. In a conversation the two come apart
 * constantly: a customer states their order number in message three and asks
 * about it in message forty, and a recency policy pages out the order number to
 * keep small talk that happened to be newer.
 *
 * <p>So each evictable message is scored against the request being answered
 * <em>now</em>:
 *
 * <pre>
 *   score = 0.7 × relevance(message, current task) + 0.3 × recency
 * </pre>
 *
 * <p>and the highest-scoring messages stay resident until the budget is spent.
 * Relevance dominates deliberately: recency is a proxy for relevance, and when a
 * direct measurement is available the proxy should not outvote it. Recency is
 * kept as a tiebreak because with two equally relevant messages the later one is
 * usually the one that superseded the earlier.
 *
 * <p>Nothing here decides what is <em>pinned</em>. The system prompt and the
 * newest turns are held resident by the caller regardless of score — the
 * immediate conversational turn is not a cache entry to be reasoned about.
 *
 * <p><b>The limitation.</b> Relevance is lexical cosine over bag-of-words, the
 * same measure the rest of Continuum uses without an embedding model. It will
 * miss a paraphrase that shares no vocabulary with the question. That makes it
 * better than position and worse than understanding, which is the honest
 * description of it.
 */
public final class WorkingSet {

    /** Relevance outweighs recency; recency is a tiebreak, not a rival signal. */
    private static final double RELEVANCE_WEIGHT = 0.7;
    private static final double RECENCY_WEIGHT = 0.3;

    private WorkingSet() {
    }

    /**
     * One candidate and why it scored what it did.
     *
     * @param index    position in the original evictable list, oldest first
     * @param resident whether it stays in L1
     */
    public record Scored(int index, Message message, double relevance, double recency,
                         double score, boolean resident) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("relevance", Math.round(relevance * 1000) / 1000.0);
            m.put("recency", Math.round(recency * 1000) / 1000.0);
            m.put("score", Math.round(score * 1000) / 1000.0);
            m.put("resident", resident);
            return m;
        }
    }

    /**
     * Chooses what stays resident.
     *
     * @param evictable    candidates, oldest first
     * @param task         the request being answered now
     * @param budgetTokens how much room there is for them
     * @param tokensOf     the caller's token estimator, so one definition is used
     * @return every candidate scored, in the original order
     */
    public static List<Scored> select(List<Message> evictable, String task, int budgetTokens,
                                      java.util.function.ToIntFunction<Message> tokensOf) {
        List<Scored> scored = new ArrayList<>();
        int n = evictable.size();
        for (int i = 0; i < n; i++) {
            Message m = evictable.get(i);
            double relevance = relevance(m, task);
            // Linear in position: the oldest scores 0, the newest evictable 1.
            double recency = n <= 1 ? 1.0 : (double) i / (n - 1);
            double score = RELEVANCE_WEIGHT * relevance + RECENCY_WEIGHT * recency;
            scored.add(new Scored(i, m, relevance, recency, score, false));
        }

        // Spend the budget on the best first, then restore the original order —
        // a conversation reordered by score is not a conversation.
        List<Scored> byScore = new ArrayList<>(scored);
        byScore.sort(Comparator.comparingDouble(Scored::score).reversed());

        int spent = 0;
        List<Scored> out = new ArrayList<>(scored);
        for (Scored s : byScore) {
            int cost = tokensOf.applyAsInt(s.message());
            if (spent + cost > budgetTokens) {
                continue;
            }
            spent += cost;
            out.set(s.index(), new Scored(s.index(), s.message(), s.relevance(), s.recency(),
                    s.score(), true));
        }
        return out;
    }

    /**
     * How much this message has to do with the task.
     *
     * <p>A message with no words in common with the question scores zero, which
     * is the point — that is exactly the message worth paging out even if it
     * arrived a moment ago.
     */
    static double relevance(Message m, String task) {
        if (task == null || task.isBlank() || m == null || m.content() == null
                || m.content().isBlank()) {
            return 0;
        }
        return Math.max(0, Math.min(1, TextVectors.cosine(task, m.content())));
    }
}
