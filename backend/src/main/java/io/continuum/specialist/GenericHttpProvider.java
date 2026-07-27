package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;

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
        return List.of("image", "text", "json", "audio");
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
                collect(list, out);
                if (!out.isEmpty()) {
                    return sorted(out);
                }
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
