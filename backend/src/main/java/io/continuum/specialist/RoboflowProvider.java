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
        StringBuilder url = new StringBuilder(base.replaceAll("/+$", ""))
                .append('/').append(modelPath.replaceAll("^/+", ""));

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
        Object image = input.get("imageBase64");
        return new Call(url.toString(), "POST", headers, image == null ? "" : image.toString());
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
                out.add(new Finding(cls, conf instanceof Number n ? n.doubleValue() : 0.0, null));
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
