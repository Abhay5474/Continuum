package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.MediaBytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deepgram — audio in, transcript out.
 *
 * <p>The developer brings their own Deepgram key; Continuum never hosts a
 * speech model. What it does is the part that is annoying to do by hand: send
 * the file in the shape Deepgram wants, and turn the answer into the same
 * {@link Evidence} every other tool produces, so the language model downstream
 * sees a transcript and not a vendor's JSON.
 *
 * <p>Chosen as the first audio adapter because it is a <b>single round trip</b>:
 * POST the audio bytes, get the transcript back. AssemblyAI needs three calls
 * and polling, which is more machinery to get wrong before anything works at
 * all.
 *
 * <p><b>Two decisions worth stating.</b>
 *
 * <p>The audio goes up as <b>raw bytes</b>, not base64 in JSON. Deepgram reads
 * the request body as the media file; sending a text encoding of it would give
 * the decoder something that is not audio.
 *
 * <p>The transcript is <b>unscored</b> evidence even though Deepgram returns a
 * confidence. That number describes how sure the recogniser is of its wording —
 * it is not a detection score, and it is not what a pipeline's
 * {@code minConfidence} means. Treating it as one would let a threshold of 0.8
 * silently discard an entire transcript that was 78% clean, which is not a
 * result anybody wants. The figure is kept in the evidence attributes, where the
 * console can show it and no filter acts on it.
 *
 * <p><b>LIVE_UNVERIFIED.</b> Written against Deepgram's documented listen API and
 * never run against the real service — this deployment refuses
 * {@code api.deepgram.com} at CONNECT. Parsing is covered by fixture tests; the
 * network path is not.
 */
public class DeepgramProvider implements SpecialistProvider {

    /** Deepgram's default model. Overridden by the specialist's model path. */
    static final String DEFAULT_MODEL = "nova-2";

    @Override
    public String name() {
        return "deepgram";
    }

    @Override
    public String label() {
        return "Deepgram (speech to text)";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.deepgram.com";
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        // "Authorization: Token <key>" — not Bearer, and not a bare header.
        return SpecialistConnectionEntity.AuthStyle.TOKEN;
    }

    @Override
    public List<String> inputKinds() {
        return List.of("audio");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath,
                          Map<String, Object> input) {
        String base = connection.getBaseUrl() == null || connection.getBaseUrl().isBlank()
                ? defaultBaseUrl()
                : connection.getBaseUrl().replaceAll("/+$", "");

        // The model path names a Deepgram model, not a URL path. Someone typing
        // "nova-2" and someone typing "/v1/listen" both mean the same thing, and
        // failing on the second would be pedantry.
        String model = modelPath == null || modelPath.isBlank() || modelPath.contains("/")
                ? DEFAULT_MODEL
                : modelPath.strip();

        StringBuilder url = new StringBuilder(base)
                .append("/v1/listen?model=").append(model)
                // Punctuation and paragraphing are what make a transcript
                // readable by a language model rather than a wall of words.
                .append("&smart_format=true&punctuate=true");
        Object language = input.get("language");
        if (language instanceof String s && !s.isBlank()) {
            url.append("&language=").append(s.strip());
        }

        Map<String, String> headers = new LinkedHashMap<>();

        // A URL the provider can fetch itself is cheaper than shipping the file
        // through Continuum, so it is preferred when the caller supplied one.
        Object audio = input.get("audioUrl");
        if (audio instanceof String s && MediaBytes.isUrl(s)) {
            headers.put("Content-Type", "application/json");
            return new Call(url.toString(), "POST", headers, Map.of("url", s.strip()));
        }

        byte[] bytes = MediaBytes.find(input, MediaBytes.AUDIO_KEYS);
        if (bytes == null || bytes.length == 0) {
            // An empty body reaches Deepgram and comes back as a 400 whose
            // message is about audio decoding, which sends the developer looking
            // at their file rather than at their pipeline's input mapping.
            throw new IllegalArgumentException(
                    "No audio was supplied. Send it as \"audioBase64\", or give \"audioUrl\" "
                            + "for a file Deepgram can fetch itself.");
        }
        headers.put("Content-Type", MediaBytes.audioContentType(bytes));
        return new Call(url.toString(), "POST", headers, bytes);
    }

    /**
     * A transcript is not a labelled score, so there is nothing to express as a
     * {@link Finding}. Everything real happens in {@link #parseEvidence}.
     */
    @Override
    public List<Finding> parse(Object responseBody) {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Evidence> parseEvidence(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body)) {
            return List.of();
        }
        Object results = body.get("results");
        if (!(results instanceof Map<?, ?> r)) {
            return List.of();
        }
        Object channels = r.get("channels");
        if (!(channels instanceof List<?> chans) || chans.isEmpty()) {
            return List.of();
        }

        List<Evidence> out = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        Double bestConfidence = null;

        for (Object c : chans) {
            if (!(c instanceof Map<?, ?> chan)) {
                continue;
            }
            Object alts = chan.get("alternatives");
            if (!(alts instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            // Deepgram ranks alternatives; the first is its best reading. Taking
            // more than one would put contradictory transcripts in front of a
            // model with no way to choose between them.
            if (!(list.get(0) instanceof Map<?, ?> alt)) {
                continue;
            }
            Object transcript = alt.get("transcript");
            if (transcript instanceof String s && !s.isBlank()) {
                if (!full.isEmpty()) {
                    full.append('\n');
                }
                full.append(s.strip());
            }
            if (alt.get("confidence") instanceof Number n
                    && (bestConfidence == null || n.doubleValue() > bestConfidence)) {
                bestConfidence = n.doubleValue();
            }
        }

        if (full.isEmpty()) {
            // Silence and a failed call look identical in an empty string, and
            // they need completely different responses.
            return List.of(Evidence.note(
                    "Deepgram processed the audio and returned no speech. The file may be "
                            + "silent, or too short to transcribe."));
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (bestConfidence != null) {
            // Recorded, never filtered on — see the class comment.
            attrs.put("recogniserConfidence", bestConfidence);
        }
        if (body.get("metadata") instanceof Map<?, ?> meta
                && meta.get("duration") instanceof Number d) {
            attrs.put("durationSeconds", d.doubleValue());
        }
        out.add(Evidence.text("Transcript", full.toString(), attrs));
        return out;
    }
}
