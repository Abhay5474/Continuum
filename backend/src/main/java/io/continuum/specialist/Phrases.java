package io.continuum.specialist;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Phrase matching that respects word boundaries.
 *
 * <p>Exists because a plain {@code contains} does not. The context builder writes
 * <em>"Weak signals, treat as unconfirmed"</em>, a model echoes it, and a
 * certainty marker of {@code "confirmed"} matches inside {@code "unconfirmed"} —
 * so the phrase that signals the strongest available doubt was read as an
 * assertion of certainty and failed the answer. Found by driving a pipeline, not
 * by reading the list.
 *
 * <p>Shared by every lexical check in this package rather than fixed in one of
 * them. Two copies of a matching rule is one copy too many, and the next list of
 * phrases someone adds will have the same negated forms waiting in it —
 * "inconclusive" contains "conclusive", "unclear" contains "clear".
 */
final class Phrases {

    /** Compiled once per phrase; the lists are small and fixed. */
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Phrases() {
    }

    /**
     * Whether {@code text} contains {@code phrase} as whole words.
     *
     * <p>Boundaries are only applied where the phrase actually begins and ends
     * with a word character, so a phrase carrying its own punctuation still
     * matches.
     */
    static boolean contains(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) {
            return false;
        }
        return CACHE.computeIfAbsent(phrase, Phrases::compile)
                .matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    private static Pattern compile(String phrase) {
        String p = phrase.strip().toLowerCase(Locale.ROOT);
        String body = Pattern.quote(p);
        String prefix = Character.isLetterOrDigit(p.charAt(0)) ? "\\b" : "";
        String suffix = Character.isLetterOrDigit(p.charAt(p.length() - 1)) ? "\\b" : "";
        return Pattern.compile(prefix + body + suffix);
    }
}
