package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.MediaBytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hugging Face — one key, a very large number of models.
 *
 * <p>The developer brings their own Hugging Face token. This is the widest
 * adapter in Continuum by some distance: the serverless inference endpoint is
 * the same URL shape for every model, so text moderation, image classification,
 * object detection, speech recognition and summarisation are all reachable by
 * changing the model id and nothing else.
 *
 * <p>That breadth is also the difficulty. <b>Hugging Face's response shape
 * depends on the model's task, not on the endpoint.</b> A classifier answers with
 * labels and scores, a detector adds a box, an ASR model answers with
 * {@code {"text": ...}}, a summariser with {@code [{"summary_text": ...}]}.
 * There is no field that says which. So this adapter recognises the shape it was
 * given rather than being told, and maps each onto the right kind of evidence.
 *
 * <p><b>Scores here are real scores.</b> Unlike a transcript's recogniser
 * confidence, a classifier's output is a class probability — "this text is 0.94
 * toxic" is exactly the kind of number a threshold is for. So classification and
 * detection results are <em>scored</em> evidence and are filtered normally. An
 * ASR result from the same provider is not, and is returned as unscored text.
 * Getting that distinction from the response shape rather than from
 * configuration is most of what this class does.
 *
 * <p><b>Cold starts are handled, not surfaced as errors.</b> A model that has not
 * been called recently is unloaded, and Hugging Face answers HTTP 503 with
 * "currently loading". The invoker treats any 4xx/5xx as a failure, which would
 * make a perfectly good tool look broken the first time it was used each day.
 * Sending {@code x-wait-for-model} makes Hugging Face hold the request until the
 * model is ready instead — one slow call rather than one confusing failure.
 *
 * <p><b>LIVE_UNVERIFIED.</b> Written against Hugging Face's documented inference
 * API and never run against the real service — {@code api-inference.huggingface.co}
 * is refused at CONNECT here. The shape mapping is covered by fixture tests; the
 * network path is not.
 */
public class HuggingFaceProvider implements SpecialistProvider {

    @Override
    public String name() {
        return "huggingface";
    }

    @Override
    public String label() {
        return "Hugging Face (hosted models)";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api-inference.huggingface.co";
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        return SpecialistConnectionEntity.AuthStyle.BEARER;
    }

    @Override
    public List<String> inputKinds() {
        return List.of("text", "image", "audio");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath,
                          Map<String, Object> input) {
        String base = connection.getBaseUrl() == null || connection.getBaseUrl().isBlank()
                ? defaultBaseUrl()
                : connection.getBaseUrl().replaceAll("/+$", "");

        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Hugging Face needs a model id — it looks like unitary/toxic-bert. There is "
                            + "no default, because the model is the entire choice being made.");
        }
        String model = modelPath.strip().replaceAll("^/+", "");
        String url = model.startsWith("models/")
                ? base + "/" + model
                : base + "/models/" + model;

        Map<String, String> headers = new LinkedHashMap<>();
        // Without this a cold model answers 503 "currently loading", which the
        // invoker would report as a failed tool. One slow first call is a much
        // better experience than a tool that looks broken every morning.
        headers.put("x-wait-for-model", "true");

        // Text first: a model given both would be answering about the wrong one,
        // and text is the only input the caller had to type deliberately.
        Object text = input.get("text");
        if (text instanceof String s && !s.isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputs", s);

            Object labels = input.get("candidateLabels");
            if (labels instanceof List<?> list && !list.isEmpty()) {
                // Zero-shot classification: the labels are supplied per request
                // rather than baked into the model.
                body.put("parameters", Map.of("candidate_labels", list));
            }
            headers.put("Content-Type", "application/json");
            return new Call(url, "POST", headers, body);
        }

        byte[] bytes = MediaBytes.find(input, MediaBytes.IMAGE_KEYS);
        String contentType = bytes == null ? null : MediaBytes.imageContentType(bytes);
        if (bytes == null) {
            bytes = MediaBytes.find(input, MediaBytes.AUDIO_KEYS);
            contentType = bytes == null ? null : MediaBytes.audioContentType(bytes);
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(
                    "No input was supplied. Send \"text\" for a text model, or \"imageBase64\" "
                            + "or \"audioBase64\" for one that takes a file.");
        }
        // Binary models take the file as the body, not base64 inside JSON.
        headers.put("Content-Type", contentType);
        return new Call(url, "POST", headers, bytes);
    }

    /**
     * Scored results only, for callers still on the {@link Finding} shape.
     *
     * <p>Text-shaped answers cannot be expressed here without inventing a
     * confidence, so they appear only in {@link #parseEvidence}.
     */
    @Override
    public List<Finding> parse(Object responseBody) {
        List<Finding> out = new ArrayList<>();
        for (Evidence e : parseEvidence(responseBody)) {
            if (e.isFindingShaped()) {
                out.add(new Finding(e.label(), e.confidence(),
                        e.attributes().get("box") instanceof Map<?, ?> b ? asRegion(b) : null));
            }
        }
        return out;
    }

    @Override
    public List<Evidence> parseEvidence(Object responseBody) {
        if (responseBody == null) {
            return List.of();
        }

        // An error is reported in a 200 body often enough to be worth checking:
        // a model id that does not exist comes back this way.
        if (responseBody instanceof Map<?, ?> m && m.get("error") != null) {
            return List.of(Evidence.note("Hugging Face returned an error: "
                    + String.valueOf(m.get("error")).strip()));
        }

        // Text-classification models answer [[{label,score}]] — an outer array
        // for the batch, of which we sent one. Unwrapping it here means the
        // single-item form and the batch form both work.
        Object body = responseBody;
        if (body instanceof List<?> outer && outer.size() == 1 && outer.get(0) instanceof List<?>) {
            body = outer.get(0);
        }

        if (body instanceof List<?> list) {
            return fromList(list);
        }
        if (body instanceof Map<?, ?> map) {
            return fromMap(map);
        }
        return List.of();
    }

    /** Labelled results: classification, detection, or a generated string. */
    private static List<Evidence> fromList(List<?> list) {
        List<Evidence> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }

            // Generation and summarisation: text, and no score to speak of.
            for (String key : List.of("generated_text", "summary_text", "translation_text")) {
                if (row.get(key) instanceof String s && !s.isBlank()) {
                    out.add(Evidence.text("Model output", s.strip(), Map.of()));
                }
            }
            if (!out.isEmpty() && row.get("label") == null) {
                continue;
            }

            Object label = row.get("label");
            Object score = row.get("score");
            if (label == null || !(score instanceof Number n)) {
                continue;
            }

            if (row.get("box") instanceof Map<?, ?> box) {
                // Object detection: a class probability plus where it is.
                out.add(Evidence.detection(label.toString(), n.doubleValue(), asRegion(box)));
            } else {
                // A classifier's score is a class probability — genuinely the
                // kind of number a confidence threshold exists to compare.
                out.add(Evidence.classification(label.toString(), n.doubleValue()));
            }
        }
        return out;
    }

    /** Single-object answers: ASR, zero-shot, or a bare text field. */
    private static List<Evidence> fromMap(Map<?, ?> map) {
        // Zero-shot classification: parallel arrays rather than objects.
        if (map.get("labels") instanceof List<?> labels
                && map.get("scores") instanceof List<?> scores) {
            List<Evidence> out = new ArrayList<>();
            for (int i = 0; i < Math.min(labels.size(), scores.size()); i++) {
                if (labels.get(i) != null && scores.get(i) instanceof Number n) {
                    out.add(Evidence.classification(labels.get(i).toString(), n.doubleValue()));
                }
            }
            return out;
        }

        // Speech recognition. Unscored, for the same reason every transcript is:
        // the model's certainty is about its wording, and a threshold applied to
        // it would discard the whole transcript rather than a weak part of one.
        if (map.get("text") instanceof String s && !s.isBlank()) {
            return List.of(Evidence.text("Transcript", s.strip(), Map.of()));
        }

        return List.of();
    }

    /** Hugging Face's box, kept as the provider gave it. */
    private static Map<String, Object> asRegion(Map<?, ?> box) {
        Map<String, Object> region = new LinkedHashMap<>();
        box.forEach((k, v) -> {
            if (k != null) {
                region.put(k.toString(), v);
            }
        });
        return region;
    }
}
