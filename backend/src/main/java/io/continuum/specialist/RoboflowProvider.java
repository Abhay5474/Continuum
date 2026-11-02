package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Roboflow's hosted inference API.
 *
 * <p>The first adapter, chosen because it is the case that motivated this layer:
 * a vision model that can see what a general-purpose model cannot, whose output
 * is a set of labelled detections with confidences.
 *
 * <p>Two Roboflow specifics are worth naming. The credential goes in a query
 * parameter rather than a header, which is unusual and is why
 * {@link SpecialistConnectionEntity.AuthStyle#QUERY} exists. And the image is
 * sent as a base64 body with a form content type rather than JSON, so the call
 * carries an explicit {@code Content-Type} that overrides the HTTP activity's
 * default.
 */
public class RoboflowProvider implements SpecialistProvider {

    /**
     * Roboflow's hosted inference endpoint for every task type.
     *
     * <p>This used to be {@code detect.roboflow.com}, with classifiers on a
     * separate {@code classify.} host. Those are the v1 API; serverless is the
     * documented endpoint for new projects and serves both. Connections saved
     * with the old host keep it — it still answers — so nothing is rewritten.
     */
    public static final String SERVERLESS = "https://serverless.roboflow.com";

    @Override
    public String name() {
        return "roboflow";
    }

    @Override
    public String label() {
        return "Roboflow";
    }

    @Override
    public String defaultBaseUrl() {
        return SERVERLESS;
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        return SpecialistConnectionEntity.AuthStyle.QUERY;
    }

    @Override
    public String defaultAuthParam() {
        return "api_key";
    }

    @Override
    public List<String> inputKinds() {
        return List.of("image");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath, Map<String, Object> input) {
        String base = connection.getBaseUrl() == null ? defaultBaseUrl() : connection.getBaseUrl();
        String path = modelPath == null ? "" : modelPath.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        Object image = input.get("imageBase64");

        if (isWorkflow(path)) {
            // A Workflow (workspace/workflows/id, or infer/workflows/workspace/id)
            // takes JSON: the image is one of the workflow's named inputs, and
            // the key goes in the body (see authorise). Sent the model API's raw
            // base64 body, Roboflow answers 422 "Input should be a valid
            // dictionary", which is how a Workflow path used to fail.
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", "base64");
            value.put("value", image == null ? "" : image.toString());
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put(WORKFLOW_IMAGE_INPUT, value);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputs", inputs);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            return new Call(base.replaceAll("/+$", "") + "/" + path, "POST", headers, body);
        }

        StringBuilder url = new StringBuilder(base.replaceAll("/+$", ""))
                .append('/').append(path);

        // Confidence and overlap are Roboflow's own filters. Passing the
        // developer's threshold down means the provider does the filtering
        // rather than shipping detections back only to discard them here.
        Object confidence = input.get("minConfidence");
        url.append("?format=json");
        if (confidence instanceof Number n) {
            url.append("&confidence=").append((int) Math.round(n.doubleValue() * 100));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");

        // The base64 image is the request body; Roboflow reads it raw.
        return new Call(url.toString(), "POST", headers, image == null ? "" : image.toString());
    }

    /**
     * The name of the workflow input the image is sent as. Roboflow's Workflow
     * builder names a workflow's image input {@code image} unless renamed.
     */
    static final String WORKFLOW_IMAGE_INPUT = "image";

    /** Whether a model path names a Roboflow Workflow rather than a model version. */
    static boolean isWorkflow(String modelPath) {
        return modelPath != null && ("/" + modelPath + "/").contains("/workflows/");
    }

    /** A Workflow reads its key from the JSON body, not the query string. */
    @Override
    @SuppressWarnings("unchecked")
    public Call authorise(Call call, String secret) {
        if (secret == null || !(call.body() instanceof Map<?, ?> body) || !body.containsKey("inputs")) {
            return call;
        }
        Map<String, Object> withKey = new LinkedHashMap<>((Map<String, Object>) body);
        withKey.put("api_key", secret);
        return new Call(call.url(), call.method(), call.headers(), withKey);
    }

    /**
     * Normalises every response shape Roboflow's inference API returns.
     *
     * <p>Detection, segmentation, keypoints and single-label classification all
     * return {@code predictions} as a list. Multi-label classification does not:
     * it returns a map from class to {@code {confidence}}. The first version of
     * this parser only read lists, so a multi-label classifier came back as "no
     * findings" — and the pipeline then told the model nothing had been seen,
     * which is a confident statement built on a parsing failure.
     */
    @Override
    public List<Finding> parse(Object responseBody) {
        if (responseBody instanceof Map<?, ?> body && body.get("outputs") instanceof List<?> outputs) {
            return parseWorkflow(outputs);
        }
        return parseModel(responseBody);
    }

    /**
     * A Workflow's answer: {@code {"outputs": [{"<output name>": ...}]}}, one
     * entry per image, each output named by whoever built the workflow. A model
     * block's output is the model API's own shape ({@code predictions} and the
     * rest) somewhere inside, so every object carrying {@code predictions} is
     * read as a model response. Other outputs (a drawn image, counts, text) are
     * passed over. A finding reported by two outputs, raw and filtered, is
     * counted once.
     */
    private List<Finding> parseWorkflow(List<?> outputs) {
        List<Finding> found = new ArrayList<>();
        collectWorkflow(outputs, found, 0);
        Map<String, Finding> unique = new LinkedHashMap<>();
        for (Finding f : found) {
            String key = f.label() + "|" + Math.round(f.confidence() * 10_000) + "|" + f.region();
            unique.putIfAbsent(key, f);
        }
        List<Finding> out = new ArrayList<>(unique.values());
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }

    private void collectWorkflow(Object node, List<Finding> out, int depth) {
        if (depth > 8 || node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            Object predictions = map.get("predictions");
            // An output named "predictions" holding the model's whole response
            // ({"image": ..., "predictions": [...]}) is a wrapper, not a
            // multi-label score map: look inside it.
            if (predictions instanceof Map<?, ?> inner && inner.containsKey("predictions")) {
                collectWorkflow(inner, out, depth + 1);
                return;
            }
            if (predictions != null) {
                out.addAll(parseModel(map));
                return;
            }
            for (Object v : map.values()) {
                collectWorkflow(v, out, depth + 1);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectWorkflow(v, out, depth + 1);
            }
        }
    }

    private List<Finding> parseModel(Object responseBody) {
        List<Finding> out = new ArrayList<>();
        if (!(responseBody instanceof Map<?, ?> body)) {
            return out;
        }
        Object predictions = body.get("predictions");
        if (predictions instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> p) {
                    Finding f = fromPrediction(p);
                    if (f != null) {
                        out.add(f);
                    }
                }
            }
        } else if (predictions instanceof Map<?, ?> byClass) {
            // Multi-label: { "dent": { "confidence": 0.52 }, ... }. Every class
            // is scored; the ones Roboflow judged present are listed separately
            // in predicted_classes, and only those are findings.
            Object predicted = body.get("predicted_classes");
            java.util.Set<String> present = new java.util.HashSet<>();
            if (predicted instanceof List<?> names) {
                for (Object n : names) {
                    present.add(String.valueOf(n));
                }
            }
            for (Map.Entry<?, ?> e : byClass.entrySet()) {
                String cls = String.valueOf(e.getKey());
                if (!present.isEmpty() && !present.contains(cls)) {
                    continue;
                }
                Object conf = e.getValue() instanceof Map<?, ?> m ? m.get("confidence") : e.getValue();
                // Only scored classes. Anything else in the map is not a class.
                if (conf instanceof Number n) {
                    out.add(new Finding(cls, n.doubleValue(), null));
                }
            }
        }
        // Strongest first: the context builder and the confidence policy both
        // care most about the top finding.
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }

    private static Finding fromPrediction(Map<?, ?> p) {
        Object cls = p.get("class");
        if (cls == null) {
            return null;
        }
        Object conf = p.get("confidence");
        Map<String, Object> region = new LinkedHashMap<>();
        for (String k : List.of("x", "y", "width", "height")) {
            Object v = p.get(k);
            if (v != null) {
                region.put(k, v);
            }
        }
        return new Finding(cls.toString(),
                conf instanceof Number n ? n.doubleValue() : 0.0,
                region.isEmpty() ? null : region);
    }
}
