package io.continuum.api;

import io.continuum.compression.PromptCompressor;
import io.continuum.firewall.PromptFirewall;
import io.continuum.loops.LoopDetector;
import io.continuum.semantic.TextVectors;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Live demos: the engine's own pure components, run on sample input.
 *
 * <p>Each endpoint here calls the same class the gateway calls on real traffic
 * — the firewall, the compressor, the cache's similarity measure — and returns
 * what it decided. None of them store anything, call a model, or touch another
 * tenant, so the console's "Demo" button can run them freely: what a visitor
 * sees is the engine's actual answer, not an illustration of it.
 */
@RestController
@RequestMapping("/api/portal/developer/demo")
public class DemoController {

    /** Sample input, not a document store: anything longer is a misuse. */
    private static final int MAX_CHARS = 20_000;

    private final PromptFirewall firewall = new PromptFirewall();
    private final PromptCompressor compressor = new PromptCompressor();

    @PostMapping("/firewall")
    public PromptFirewall.InboundResult firewall(@RequestBody TextRequest body) {
        return firewall.scanInbound(limit(body.text()), true, true);
    }

    @PostMapping("/compress")
    public Map<String, Object> compress(@RequestBody CompressRequest body) {
        double ratio = body.ratio() == null ? 0.5 : body.ratio();
        PromptCompressor.Result r = compressor.compress(limit(body.text()), ratio, 0);
        return Map.of("text", r.text(), "originalTokens", r.originalTokens(),
                "compressedTokens", r.compressedTokens(), "protectedSpans", r.protectedSpans(),
                "achievedRatio", r.achievedRatio());
    }

    /** Scores each candidate against the prompt with the semantic cache's own measure. */
    @PostMapping("/similarity")
    public List<Map<String, Object>> similarity(@RequestBody SimilarityRequest body) {
        String prompt = limit(body.prompt());
        List<String> candidates = body.candidates() == null ? List.of() : body.candidates();
        if (candidates.size() > 20) {
            throw new IllegalArgumentException("At most 20 candidates.");
        }
        var incoming = TextVectors.termFrequency(prompt);
        return candidates.stream()
                .map(c -> Map.<String, Object>of("text", c,
                        "score", TextVectors.cosine(incoming, TextVectors.termFrequency(limit(c)))))
                .toList();
    }

    /** The loop detector alone, without recording a caught loop in the account's history. */
    @PostMapping("/loops")
    public Map<String, Object> loops(@RequestBody LoopRequest body) {
        List<String> steps = body.steps() == null ? List.of() : body.steps();
        if (steps.size() > 200) {
            throw new IllegalArgumentException("At most 200 steps.");
        }
        steps.forEach(DemoController::limit);
        return LoopDetector.inspect(steps, body.progress()).describe();
    }

    private static String limit(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("Demo input is limited to " + MAX_CHARS + " characters.");
        }
        return text;
    }

    public record TextRequest(String text) {
    }

    public record CompressRequest(String text, Double ratio) {
    }

    public record SimilarityRequest(String prompt, List<String> candidates) {
    }

    public record LoopRequest(List<String> steps, List<Boolean> progress) {
    }
}
