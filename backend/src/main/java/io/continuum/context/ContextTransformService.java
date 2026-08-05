package io.continuum.context;

import io.continuum.persistence.entity.ContextTransformEntity;
import io.continuum.persistence.repository.ContextTransformRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks a transformer, runs it, and records what it did.
 *
 * <p>The layer's front door. Two decisions live here rather than in the
 * transformers.
 *
 * <p><b>Detection is by content.</b> A developer posting a file should not have
 * to declare what it is: they often do not know, their upload widget often lies
 * about the content type, and being wrong about the declaration produces a worse
 * failure than being unsure about the bytes. Transformers are asked in order and
 * the first that recognises the input wins.
 *
 * <p><b>An unrecognised input is passed through, not rejected.</b> Continuum is
 * a reliability layer; a context transformer that returns 400 for anything it
 * has no opinion about would make every pipeline more fragile than it was
 * before this existed. The caller gets their input back with
 * {@link ContextType#PASSTHROUGH} and an honest note.
 */
@Service
public class ContextTransformService {

    private static final Logger log = LoggerFactory.getLogger(ContextTransformService.class);

    private final List<ContextTransformer> transformers;
    private final ContextTransformRepository repo;

    public ContextTransformService(List<ContextTransformer> transformers,
                                   ContextTransformRepository repo) {
        this.transformers = transformers;
        this.repo = repo;
    }

    /**
     * @param context   the canonical form, or null when nothing recognised it
     * @param rendered  what a model would be given
     * @param stats     measured, not estimated from the transformer's opinion
     * @param transform which transformer ran, or null
     */
    public record Result(CanonicalContext context, String rendered, TokenStats stats,
                         String transformer, String rawBaseline) {

        public boolean transformed() {
            return context != null && context.type() != ContextType.PASSTHROUGH;
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transformed", transformed());
            m.put("transformer", transformer);
            m.put("contextType", context == null ? ContextType.PASSTHROUGH.name()
                    : context.type().name());
            m.put("contextLabel", context == null ? ContextType.PASSTHROUGH.label()
                    : context.type().label());
            m.put("sourceName", context == null ? null : context.sourceName());
            m.put("rendered", rendered);
            m.put("tokenStats", stats.toMap());
            m.put("structure", context == null ? Map.of() : context.structure());
            m.put("ambiguities", context == null ? List.of()
                    : context.ambiguities().stream().map(Ambiguity::toMap).toList());
            m.put("provenance", context == null ? List.of()
                    : context.provenance().stream().limit(50).map(SourceRef::toMap).toList());
            m.put("data", context == null ? Map.of() : context.describe());
            return m;
        }
    }

    /** Which transformers exist, for the console and the capabilities endpoint. */
    public List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContextTransformer t : transformers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("label", t.label());
            m.put("produces", t.produces().name());
            m.put("producesLabel", t.produces().label());
            m.put("summary", t.produces().summary());
            out.add(m);
        }
        return out;
    }

    /**
     * Transforms without recording — used by the pipeline, where the trace is
     * already the record and a second one would double-count every run.
     */
    public Result transform(byte[] input, String filename, RenderBudget budget) {
        ContextTransformer chosen = null;
        for (ContextTransformer t : transformers) {
            try {
                if (t.supports(input, filename)) {
                    chosen = t;
                    break;
                }
            } catch (RuntimeException e) {
                // A transformer that throws while sniffing must not stop the
                // ones after it from being asked.
                log.debug("Transformer {} threw during detection: {}", t.name(), e.toString());
            }
        }

        if (chosen == null) {
            String raw = new String(input == null ? new byte[0] : input, StandardCharsets.UTF_8);
            return new Result(null, raw, new TokenStats(
                    io.continuum.compression.PromptCompressor.estimateTokens(raw),
                    io.continuum.compression.PromptCompressor.estimateTokens(raw)),
                    null, raw);
        }

        CanonicalContext context;
        String raw;
        try {
            context = chosen.transform(input, filename);
            raw = chosen.rawTextBaseline(input, filename);
        } catch (RuntimeException e) {
            // The interface forbids throwing. A defective transformer still must
            // not take down the request that used it.
            log.warn("Transformer {} threw: {}", chosen.name(), e.toString());
            String fallback = new String(input == null ? new byte[0] : input, StandardCharsets.UTF_8);
            return new Result(null, fallback, TokenStats.of(fallback, fallback), null, fallback);
        }

        String rendered = context.render(budget);
        return new Result(context, rendered, TokenStats.of(raw, rendered), chosen.name(), raw);
    }

    /** Transforms and records it, for the console's history and the API. */
    @Transactional
    public Result transformAndRecord(String developerId, byte[] input, String filename,
                                     RenderBudget budget) {
        Result r = transform(input, filename, budget);
        record(developerId, r, filename, input == null ? 0 : input.length);
        return r;
    }

    /**
     * Writes one history row.
     *
     * <p>Separate from {@link #transformAndRecord} because the two callers want
     * different things recorded. Someone who uploaded a file on the console
     * asked a question, and "nothing recognised this" is the answer — worth a
     * row. The gateway asks about every user message that goes past, and
     * recording the ones it had no opinion about would write a row per request
     * and bury the real transforms in them.
     */
    @Transactional
    public void record(String developerId, Result r, String sourceName, int inputBytes) {
        try {
            ContextTransformEntity e = new ContextTransformEntity(developerId,
                    r.context() == null ? ContextType.PASSTHROUGH.name() : r.context().type().name(),
                    r.transformer(), sourceName, inputBytes,
                    r.stats().before(), r.stats().after(),
                    r.context() == null ? 0 : r.context().ambiguities().size(),
                    summarise(r));
            repo.save(e);
        } catch (RuntimeException e) {
            // Observability is not worth failing a transformation over.
            log.debug("Could not record a context transform: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String developerId, int limit) {
        return repo.findByDeveloperIdOrderByCreatedAtDesc(developerId).stream()
                .limit(Math.max(1, Math.min(limit, 100)))
                .map(ContextTransformEntity::describe)
                .toList();
    }

    /** A one-line structure summary, kept on the row so history reads usefully. */
    private static String summarise(Result r) {
        if (r.context() == null) {
            return "No transformer recognised this input.";
        }
        Map<String, Object> s = r.context().structure();
        return s.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
