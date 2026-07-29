package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Any JSON endpoint the developer already runs.
 *
 * <p>This is the adapter that stops the layer from being a Roboflow feature. A
 * developer with their own model behind FastAPI, a SageMaker endpoint, or an
 * internal service gets the same pipeline, the same credential handling and the
 * same normalised output as a catalogue provider.
 *
 * <p>Parsing is forgiving by necessity: nobody agreed a schema. It looks for the
 * shapes that actually occur — a list of predictions, a single label/score pair,
 * a map of label to score — and returns nothing rather than throwing when it
 * finds none. "No signal" is a legitimate answer that the confidence policy
 * knows how to handle; an exception in the middle of a request is not.
 */
public class GenericHttpProvider implements SpecialistProvider {

    /**
     * Keys that plausibly carry recovered text — OCR output, a transcript, a
     * summary. None of these were recognised before: measured against the real
     * parser, {@code {"text": "INVOICE 4471"}} produced zero findings, which is
     * why every OCR and transcription entry in the catalogue was installable and
     * useless.
     */
    private static final List<String> TEXT_KEYS = List.of(
            "text", "full_text", "fullText", "transcript", "transcription",
            "content", "extracted_text", "extractedText", "ocr_text", "ocrText", "value");

    /** Keys that plausibly carry a map of named values pulled from a document. */
    private static final List<String> FIELD_MAP_KEYS =
            List.of("fields", "entities", "extracted", "data", "attributes", "properties");

    /** Keys that plausibly carry tabular rows. */
    private static final List<String> ROW_KEYS = List.of("rows", "records", "items", "table");

    /** Keys that plausibly carry a list of results. */
    private static final List<String> LIST_KEYS =
            List.of("predictions", "detections", "results", "findings", "outputs", "labels");

    /** Keys that plausibly carry a label, and a confidence. */
    private static final List<String> LABEL_KEYS = List.of("class", "label", "name", "category", "tag");
    private static final List<String> SCORE_KEYS = List.of("confidence", "score", "probability", "prob");

    @Override
    public String name() {
        return "http";
    }

    @Override
    public String label() {
        return "Custom HTTP endpoint";
    }

    @Override
    public String defaultBaseUrl() {
        // No fixed home: the developer supplies it, which is the whole point.
        return null;
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        return SpecialistConnectionEntity.AuthStyle.BEARER;
    }

    @Override
    public List<String> inputKinds() {
        return List.of("image", "text", "json", "audio", "document");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath, Map<String, Object> input) {
        String base = connection.getBaseUrl() == null ? "" : connection.getBaseUrl().replaceAll("/+$", "");
        String url = modelPath == null || modelPath.isBlank()
                ? base
                : base + "/" + modelPath.replaceAll("^/+", "");
        return new Call(url, "POST", new LinkedHashMap<>(), input);
    }

    @Override
    public List<Finding> parse(Object responseBody) {
        List<Finding> out = new ArrayList<>();
        if (responseBody == null) {
            return out;
        }
        if (responseBody instanceof List<?> list) {
            collect(list, out);
            return sorted(out);
        }
        if (!(responseBody instanceof Map<?, ?> body)) {
            return out;
        }
        for (String key : LIST_KEYS) {
            Object v = body.get(key);
            if (v instanceof List<?> list) {
                // A recognised results key IS the answer, even when it is empty.
                // Falling through on an empty list sent the score-map scrape
                // over the envelope's own bookkeeping fields — a detector
                // reporting nothing came back with "bytes_received" as a finding
                // at 128, which the policy then read as strong evidence.
                collect(list, out);
                return sorted(out);
            }
        }
        // A single result at the top level: {"label": "wound", "score": 0.87}
        Finding single = toFinding(body);
        if (single != null) {
            out.add(single);
            return out;
        }
        // A score map: {"wound": 0.87, "bruise": 0.12}
        for (Map.Entry<?, ?> e : body.entrySet()) {
            if (e.getKey() != null && e.getValue() instanceof Number n) {
                out.add(new Finding(e.getKey().toString(), n.doubleValue(), null));
            }
        }
        return sorted(out);
    }

    /**
     * The generalised parser.
     *
     * <p>Tried in a deliberate order, most specific first, and it <b>stops at
     * the first shape it recognises</b>. Falling through after a match is how
     * the earlier version scraped an envelope's bookkeeping fields and reported
     * {@code bytes_received} as a finding at 128.
     *
     * <ol>
     *   <li>A recognised results list — detections or classifications.</li>
     *   <li>Recovered text under any of the usual keys.</li>
     *   <li>A map of named fields.</li>
     *   <li>Tabular rows.</li>
     *   <li>A single labelled score at the top level.</li>
     *   <li>A bare score map.</li>
     * </ol>
     *
     * <p>A body it cannot read yields nothing rather than throwing. "No signal"
     * is an outcome the confidence policy knows how to handle; an exception in
     * the middle of a customer request is not.
     */
    @Override
    public List<Evidence> parseEvidence(Object responseBody) {
        List<Evidence> out = new ArrayList<>();
        if (responseBody == null) {
            return out;
        }

        // A bare string body is the whole answer — some OCR and transcription
        // endpoints answer text/plain rather than JSON.
        if (responseBody instanceof String s) {
            if (!s.isBlank()) {
                out.add(Evidence.text(null, s, Map.of()));
            }
            return out;
        }

        if (responseBody instanceof List<?> list) {
            collectEvidence(list, out);
            return Evidence.normalise(out);
        }
        if (!(responseBody instanceof Map<?, ?> body)) {
            return out;
        }

        // 1. Detections and classifications keep their existing precedence.
        for (String key : LIST_KEYS) {
            Object v = body.get(key);
            if (v instanceof List<?> list) {
                collectEvidence(list, out);
                return Evidence.normalise(out);
            }
        }

        // 2. Recovered text.
        for (String key : TEXT_KEYS) {
            Object v = body.get(key);
            if (v instanceof String s && !s.isBlank()) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                for (String meta : List.of("language", "page", "pages", "duration", "confidence")) {
                    Object mv = body.get(meta);
                    if (mv != null && !(mv instanceof Map) && !(mv instanceof List)) {
                        attrs.put(meta, mv);
                    }
                }
                out.add(Evidence.text(key.equals("text") ? null : key, s, attrs));
                // Segments alongside a transcript are the same content again;
                // adding them would duplicate every word in the prompt.
                return out;
            }
        }

        // 3. A map of named fields.
        for (String key : FIELD_MAP_KEYS) {
            Object v = body.get(key);
            if (v instanceof Map<?, ?> fields && !fields.isEmpty()) {
                collectFields(fields, out);
                return out;
            }
            if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
                collectEvidence(list, out);
                return Evidence.normalise(out);
            }
        }

        // 4. Tabular rows.
        for (String key : ROW_KEYS) {
            Object v = body.get(key);
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> row) {
                        out.add(Evidence.row(asStringKeyed(row)));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }

        // 5. A single labelled score at the top level.
        Finding single = toFinding(body);
        if (single != null) {
            out.add(Evidence.classification(single.label(), single.confidence()));
            return out;
        }

        // 6. A bare score map — but only when the whole map plausibly is one.
        //
        //    Requiring *every* value to be a number inside a confidence range is
        //    what separates {"toxicity": 0.91, "threat": 0.02} from
        //    {"invoiceNumber": "4471", "total": 12500}. The earlier rule claimed
        //    the second as well, scored the invoice total as a confidence of
        //    12500, and then lost the whole response when normalisation
        //    correctly dropped it — an extraction that returned nothing at all.
        if (looksLikeScoreMap(body)) {
            for (Map.Entry<?, ?> e : body.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number n) {
                    out.add(Evidence.classification(e.getKey().toString(), n.doubleValue()));
                }
            }
            if (!out.isEmpty()) {
                return Evidence.normalise(out);
            }
        }

        // 7. A flat map of values is a field set by another name — the shape a
        //    document extractor produces when it does not wrap its output.
        //    Reached only when nothing above matched, so it cannot steal an
        //    envelope from a detector.
        collectFields(body, out);
        return out;
    }

    /**
     * Whether a flat map is a set of scores rather than a set of extracted values.
     *
     * <p>Every value must be a number, and every number must sit inside a range a
     * confidence could plausibly occupy. One string, or one figure like an
     * invoice total, and it is data — which is the safe reading, because
     * mistaking data for scores invents confidences and mistaking scores for
     * data merely presents them as fields.
     */
    private static boolean looksLikeScoreMap(Map<?, ?> body) {
        if (body.isEmpty()) {
            return false;
        }
        for (Object v : body.values()) {
            if (!(v instanceof Number n)) {
                return false;
            }
            double d = n.doubleValue();
            if (Double.isNaN(d) || d < 0 || d > 100) {
                return false;
            }
        }
        return true;
    }

    /** Detections, classifications, or bare labels from a list. */
    private static void collectEvidence(List<?> list, List<Evidence> out) {
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Finding f = toFinding(m);
                if (f != null) {
                    out.add(f.region() == null
                            ? Evidence.classification(f.label(), f.confidence())
                            : Evidence.detection(f.label(), f.confidence(), f.region()));
                    continue;
                }
                // No label anywhere: a row of data rather than a result.
                out.add(Evidence.row(asStringKeyed(m)));
            } else if (o instanceof String s && !s.isBlank()) {
                // A bare list of labels carries no confidence. This used to be
                // recorded as 1.0, which asserted certainty the provider never
                // claimed; it is now honestly unscored.
                out.add(new Evidence(Evidence.Kind.CLASSIFICATION, s, null, null, Map.of()));
            }
        }
    }

    /** Named values, skipping nested structures a prompt cannot use. */
    private static void collectFields(Map<?, ?> fields, List<Evidence> out) {
        for (Map.Entry<?, ?> e : fields.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null || v instanceof Map || v instanceof List) {
                continue;
            }
            out.add(Evidence.field(k.toString(), String.valueOf(v), null));
        }
    }

    private static Map<String, Object> asStringKeyed(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && !(v instanceof Map) && !(v instanceof List)) {
                out.put(k.toString(), v);
            }
        });
        return out;
    }

    private static void collect(List<?> list, List<Finding> out) {
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Finding f = toFinding(m);
                if (f != null) {
                    out.add(f);
                }
            } else if (o instanceof String s) {
                // A bare list of labels carries no confidence. Reporting 0
                // would read as "certainly not"; these are unscored, so they
                // get a neutral 1.0 and the policy can decide what that means.
                out.add(new Finding(s, 1.0, null));
            }
        }
    }

    private static Finding toFinding(Map<?, ?> m) {
        String label = null;
        for (String k : LABEL_KEYS) {
            Object v = m.get(k);
            if (v != null) {
                label = v.toString();
                break;
            }
        }
        if (label == null) {
            return null;
        }
        double score = 1.0;
        for (String k : SCORE_KEYS) {
            Object v = m.get(k);
            if (v instanceof Number n) {
                score = n.doubleValue();
                break;
            }
        }
        Map<String, Object> region = new LinkedHashMap<>();
        for (String k : List.of("x", "y", "width", "height", "box", "bbox")) {
            Object v = m.get(k);
            if (v != null) {
                region.put(k, v);
            }
        }
        return new Finding(label, score, region.isEmpty() ? null : region);
    }

    private static List<Finding> sorted(List<Finding> out) {
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }
}
