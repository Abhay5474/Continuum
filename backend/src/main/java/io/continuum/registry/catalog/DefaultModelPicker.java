package io.continuum.registry.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses a provider's default model, and the replacement for one that went away.
 *
 * <p>Deterministic and explainable, no model in the loop:
 * <ol>
 *   <li>a pinned model, when it is usable;</li>
 *   <li>the model configured for the deployment, when it is usable;</li>
 *   <li>the current default, when it is still usable — a newer model appearing
 *       is not a reason to change what every request runs on;</li>
 *   <li>otherwise the best candidate: same family as the model it replaces
 *       ({@code gemini-3.5-flash} → {@code gemini-3.8-flash}), then stable over
 *       preview, then stronger, then newer, then larger context.</li>
 * </ol>
 */
public final class DefaultModelPicker {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)*");
    private static final Pattern PARAMS = Pattern.compile("(\\d+(?:\\.\\d+)?)b\\b");
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private DefaultModelPicker() {
    }

    /** A usable model, as far as choosing is concerned. */
    public record Candidate(String id, boolean preview, int contextWindow, long createdEpoch) {
    }

    public record Choice(String model, String reason) {
    }

    public static Optional<Choice> pick(List<Candidate> usable, String pinned, String configured,
                                        String current, String replacing) {
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        if (pinned != null && has(usable, pinned)) {
            return Optional.of(new Choice(pinned, "pinned by the operator"));
        }
        if (configured != null && !configured.isBlank() && has(usable, configured)) {
            return Optional.of(new Choice(configured, "configured for this deployment"));
        }
        if (current != null && has(usable, current)) {
            return Optional.of(new Choice(current, "still available"));
        }
        String like = replacing != null ? replacing : current;
        Candidate best = usable.stream().min(order(like)).orElseThrow();
        return Optional.of(new Choice(best.id(), reasonFor(best, like)));
    }

    /**
     * The model a request naming {@code gone} should be sent to instead.
     *
     * <p>Predictable rather than clever: the provider's default when it is the
     * same family ({@code gemini-2.5-flash} → the default {@code gemini-3.5-flash});
     * otherwise the newest usable model of that family; otherwise the default;
     * otherwise the best usable model.
     */
    public static Optional<String> replacementFor(String gone, List<Candidate> usable, String currentDefault) {
        List<Candidate> others = usable.stream().filter(c -> !c.id().equals(gone)).toList();
        boolean defaultUsable = currentDefault != null && others.stream().anyMatch(c -> c.id().equals(currentDefault));
        String fam = family(gone);
        if (defaultUsable && fam.equals(family(currentDefault))) {
            return Optional.of(currentDefault);
        }
        Optional<String> sameFamily = others.stream().filter(c -> fam.equals(family(c.id()))).min(order(gone)).map(Candidate::id);
        if (sameFamily.isPresent()) {
            return sameFamily;
        }
        if (defaultUsable) {
            return Optional.of(currentDefault);
        }
        return others.stream().min(order(gone)).map(Candidate::id);
    }

    private static Comparator<Candidate> order(String like) {
        String fam = like == null ? null : family(like);
        return Comparator
                .comparing((Candidate c) -> fam != null && fam.equals(family(c.id())) ? 0 : 1)
                .thenComparing(c -> c.preview() ? 1 : 0)
                .thenComparing(c -> -strength(c.id()))
                .thenComparing(c -> -version(c.id()))
                .thenComparing(c -> -c.createdEpoch())
                .thenComparing(c -> -c.contextWindow())
                .thenComparing(Candidate::id);
    }

    private static String reasonFor(Candidate c, String like) {
        if (like != null && family(like).equals(family(c.id()))) {
            return "the newest usable model in the same family as " + like;
        }
        return (c.preview() ? "the strongest usable model" : "the strongest stable model")
                + (like == null ? "" : " — nothing in the same family as " + like + " is usable");
    }

    private static boolean has(List<Candidate> usable, String id) {
        return usable.stream().anyMatch(c -> c.id().equals(id));
    }

    /** A model's name with its version numbers taken out: gemini-3.5-flash and gemini-3.8-flash share one. */
    public static String family(String id) {
        return NUMBER.matcher(id.toLowerCase(Locale.ROOT)).replaceAll("#");
    }

    /** Relative strength from the name: 3 strong, 2 general, 1 small. */
    public static int strength(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("lite") || s.contains("instant") || s.contains("mini") || s.contains("nano")) {
            return 1;
        }
        if (s.contains("pro") || s.contains("versatile")) {
            return 3;
        }
        Matcher m = PARAMS.matcher(s);
        if (m.find()) {
            double b = Double.parseDouble(m.group(1));
            return b >= 60 ? 3 : b >= 25 ? 2 : 1;
        }
        return 2;
    }

    /** The tier routing reads (see {@code ModelFallbackPolicy}). */
    public static String tierOf(String id) {
        return switch (strength(id)) {
            case 3 -> id.toLowerCase(Locale.ROOT).contains("pro") ? "pro" : "versatile";
            case 1 -> "lite";
            default -> "flash";
        };
    }

    /** The first version number in the name, for "newer" — 3.8 beats 3.5. */
    static double version(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        // Parameter counts ("120b") are sizes, not versions.
        s = PARAMS.matcher(s).replaceAll("");
        Matcher m = VERSION.matcher(s);
        if (!m.find()) {
            return 0;
        }
        try {
            String v = m.group(1);
            int dot = v.indexOf('.');
            return dot < 0 ? Double.parseDouble(v) : Double.parseDouble(v.substring(0, Math.min(v.length(), dot + 3)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
