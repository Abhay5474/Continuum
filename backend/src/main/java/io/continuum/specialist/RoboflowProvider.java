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
        return "https://detect.roboflow.com";
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

    @Override
    @SuppressWarnings("unchecked")
    public List<Finding> parse(Object responseBody) {
        List<Finding> out = new ArrayList<>();
        if (!(responseBody instanceof Map<?, ?> body)) {
            return out;
        }
        Object predictions = body.get("predictions");
        if (!(predictions instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> p)) {
                continue;
            }
            Object cls = p.get("class");
            Object conf = p.get("confidence");
            if (cls == null) {
                continue;
            }
            Map<String, Object> region = new LinkedHashMap<>();
            for (String k : List.of("x", "y", "width", "height")) {
                Object v = p.get(k);
                if (v != null) {
                    region.put(k, v);
                }
            }
            out.add(new Finding(cls.toString(),
                    conf instanceof Number n ? n.doubleValue() : 0.0,
                    region.isEmpty() ? null : region));
        }
        // Strongest first: the context builder and the confidence policy both
        // care most about the top finding.
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }
}
