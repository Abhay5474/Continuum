package io.continuum.gateway;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * What Continuum did to one request, in words a caller can show.
 *
 * <p>Most features change a request without leaving a trace in the answer: the
 * firewall redacts a card number, compression drops filler, the context
 * optimizer pages out old turns, and the response looks the same either way.
 * The console sees all of it; the application that sent the request saw none
 * of it. This collects one short line per feature that acted, so the gateway
 * can return them ({@code X-Continuum-Applied}, and {@code continuum.applied}
 * on the OpenAI-shaped endpoint).
 *
 * <p>Thread-confined, like the request it describes: {@link GatewayService#chat}
 * starts a fresh list, the stages add to it, and the controller takes it once
 * the answer is ready. Taking it clears it, so a pooled thread never carries
 * one request's lines into the next.
 */
public final class AppliedFeatures {

    /** A runaway loop must not grow a header without bound. */
    private static final int MAX_LINES = 24;

    private static final ThreadLocal<LinkedHashSet<String>> CURRENT = new ThreadLocal<>();

    private AppliedFeatures() {
    }

    /** Starts a fresh list for the request now running on this thread. */
    static void begin() {
        CURRENT.set(new LinkedHashSet<>());
    }

    /** Records that a feature acted. A no-op outside a gateway request. */
    public static void note(String line) {
        LinkedHashSet<String> lines = CURRENT.get();
        if (lines != null && line != null && !line.isBlank() && lines.size() < MAX_LINES) {
            lines.add(line.strip());
        }
    }

    /** The lines recorded so far, and clears them. Empty when nothing acted. */
    public static List<String> take() {
        LinkedHashSet<String> lines = CURRENT.get();
        CURRENT.remove();
        return lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * The lines as one header value. Header values must be plain ASCII on one
     * line, so the arrows and dashes the lines use are spelled out.
     */
    public static String asHeader(List<String> lines) {
        return String.join("; ", lines)
                .replace("→", "->")
                .replace("—", "-")
                .replace("·", "-")
                .replaceAll("[^\\x20-\\x7E]", "");
    }
}
