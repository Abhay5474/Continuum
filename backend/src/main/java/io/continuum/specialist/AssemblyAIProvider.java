package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.MediaBytes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AssemblyAI — audio in, transcript out, over three round trips.
 *
 * <p>The developer brings their own AssemblyAI key. What makes this adapter
 * different from {@link DeepgramProvider} is that AssemblyAI does not answer in
 * one call:
 *
 * <ol>
 *   <li>POST the audio bytes to {@code /v2/upload}, which returns a URL;</li>
 *   <li>POST that URL to {@code /v2/transcript}, which queues a job;</li>
 *   <li>GET {@code /v2/transcript/{id}} until the job reports {@code completed}.</li>
 * </ol>
 *
 * <p>All three live here, driven by {@link #next}, because that sequence is a
 * fact about AssemblyAI and about nothing else. Putting it in the invoker would
 * make every other adapter pay attention to a problem only this one has.
 *
 * <p><b>The honest limitation: polling inside a synchronous request.</b> A
 * pipeline run is one HTTP request from the caller's application, and this
 * adapter holds it open while the job finishes. AssemblyAI is roughly real-time,
 * so a one-minute recording usually lands inside a normal timeout — but a long
 * file will not. When the budget runs out the adapter says the job is still
 * processing and gives back the id, which is a truthful partial answer. It does
 * not pretend the audio was silent. Long-form audio wants a callback, and that
 * is a larger change than an adapter.
 *
 * <p><b>LIVE_UNVERIFIED.</b> Written against AssemblyAI's documented API and
 * never run against the real service — {@code api.assemblyai.com} is refused at
 * CONNECT here. The three-stage sequence and the parsing are covered by fixture
 * tests; the network path is not.
 */
public class AssemblyAIProvider implements SpecialistProvider {

    /** Upload, submit, then up to this many polls before giving up. */
    private static final int MAX_ROUNDS = 18;

    /** Between polls. Short enough to feel responsive, long enough to be polite. */
    private static final int POLL_MILLIS = 1500;

    @Override
    public String name() {
        return "assemblyai";
    }

    @Override
    public String label() {
        return "AssemblyAI (speech to text)";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.assemblyai.com";
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        // The bare key, with no scheme word in front of it.
        return SpecialistConnectionEntity.AuthStyle.HEADER;
    }

    @Override
    public String defaultAuthParam() {
        return "authorization";
    }

    @Override
    public List<String> inputKinds() {
        return List.of("audio");
    }

    @Override
    public int maxRounds() {
        return MAX_ROUNDS;
    }

    private static String base(SpecialistConnectionEntity connection) {
        String b = connection.getBaseUrl();
        return b == null || b.isBlank()
                ? "https://api.assemblyai.com"
                : b.replaceAll("/+$", "");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath,
                          Map<String, Object> input) {
        String base = base(connection);

        // A URL AssemblyAI can fetch skips the upload entirely, so the sequence
        // starts one round shorter.
        Object url = input.get("audioUrl");
        if (url instanceof String s && MediaBytes.isUrl(s)) {
            return submit(base, s.strip(), input);
        }

        byte[] bytes = MediaBytes.find(input, MediaBytes.AUDIO_KEYS);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(
                    "No audio was supplied. Send it as \"audioBase64\", or give \"audioUrl\" "
                            + "for a file AssemblyAI can fetch itself.");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream");
        return new Call(base + "/v2/upload", "POST", headers, bytes);
    }

    /** Stage two: queue a transcription job for an uploaded or supplied URL. */
    private static Call submit(String base, String audioUrl, Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio_url", audioUrl);
        if (input != null && input.get("language") instanceof String s && !s.isBlank()) {
            body.put("language_code", s.strip());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        return new Call(base + "/v2/transcript", "POST", headers, body);
    }

    /**
     * Drives the sequence from whatever the provider just said.
     *
     * <p>Stateless on purpose: each stage is recognised by a field only that
     * stage returns — {@code upload_url} from the upload, a {@code status} from
     * the job. Carrying a state machine here would mean the invoker had to keep
     * it, and a retry would resume in the wrong place.
     */
    @Override
    public Next next(SpecialistConnectionEntity connection, Object responseBody, int round) {
        if (!(responseBody instanceof Map<?, ?> body)) {
            return null;
        }
        String base = base(connection);

        // Stage one answered: an upload URL, which becomes the job's input.
        if (body.get("upload_url") instanceof String uploaded && !uploaded.isBlank()) {
            return Next.of(submit(base, uploaded.strip(), null));
        }

        Object status = body.get("status");
        Object id = body.get("id");
        if (!(status instanceof String s) || !(id instanceof String jobId) || jobId.isBlank()) {
            return null;
        }
        // Terminal either way; polling a finished job would just repeat it.
        if (s.equalsIgnoreCase("completed") || s.equalsIgnoreCase("error")) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        Call poll = new Call(base + "/v2/transcript/" + jobId, "GET", headers, null);
        // The first poll goes out immediately: a short clip is often already
        // done, and waiting first would add a second to every transcription.
        return round <= 2 ? Next.of(poll) : Next.after(POLL_MILLIS, poll);
    }

    @Override
    public List<Finding> parse(Object responseBody) {
        return List.of();
    }

    @Override
    public List<Evidence> parseEvidence(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body)) {
            return List.of();
        }

        String status = body.get("status") instanceof String s ? s.toLowerCase() : "";

        if (status.equals("error")) {
            Object message = body.get("error");
            return List.of(Evidence.note("AssemblyAI could not transcribe this audio"
                    + (message instanceof String m && !m.isBlank() ? ": " + m.strip() : ".")));
        }

        if (status.equals("queued") || status.equals("processing")) {
            // Ran out of budget mid-poll. Said plainly, with the id, rather than
            // reported as an empty transcript — those need opposite responses.
            Object id = body.get("id");
            return List.of(Evidence.note(
                    "AssemblyAI is still transcribing this audio; it did not finish inside this "
                            + "pipeline's timeout. Raise the specialist's timeout for long "
                            + "recordings."
                            + (id instanceof String s ? " Job id: " + s + "." : "")));
        }

        Object text = body.get("text");
        if (!(text instanceof String transcript) || transcript.isBlank()) {
            return List.of(Evidence.note(
                    "AssemblyAI processed the audio and returned no speech. The file may be "
                            + "silent, or too short to transcribe."));
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (body.get("confidence") instanceof Number n) {
            // Metadata, not a filter. A recogniser's certainty about its wording
            // is not a detection score, and a threshold applied to it would
            // discard whole transcripts.
            attrs.put("recogniserConfidence", n.doubleValue());
        }
        if (body.get("audio_duration") instanceof Number d) {
            attrs.put("durationSeconds", d.doubleValue());
        }
        if (body.get("language_code") instanceof String lang && !lang.isBlank()) {
            attrs.put("language", lang);
        }
        return List.of(Evidence.text("Transcript", transcript.strip(), attrs));
    }
}
