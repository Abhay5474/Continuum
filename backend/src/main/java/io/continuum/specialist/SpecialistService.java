package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.SpecialistEntity;
import io.continuum.persistence.repository.SpecialistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns specialists and the probe that configures them.
 *
 * <p>The probe is the honest form of "automatic configuration". A catalogue
 * entry cannot tell you the shape of a model's response — Roboflow is uniform,
 * Hugging Face varies by task, a developer's own endpoint is whatever they
 * chose. So Continuum sends one real request with a sample input, keeps the
 * answer, and shows it. What the developer sees is not a description of their
 * integration; it is their integration, having run.
 *
 * <p>A specialist stays {@code DRAFT} until a probe succeeds. Allowing an
 * unproven one into a pipeline moves the failure from configuration time, where
 * a developer is looking at it, to request time, where a customer is.
 */
@Service
public class SpecialistService {

    /**
     * A 1×1 PNG. Enough for a vision endpoint to accept the request and answer
     * in its own shape, which is all the probe needs — the point is to learn the
     * response format, not to detect anything.
     */
    private static final String SAMPLE_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final SpecialistRepository repo;
    private final SpecialistConnectionService connections;
    private final SpecialistInvoker invoker;
    private final ObjectMapper mapper;

    public SpecialistService(SpecialistRepository repo, SpecialistConnectionService connections,
                             SpecialistInvoker invoker, ObjectMapper mapper) {
        this.repo = repo;
        this.connections = connections;
        this.invoker = invoker;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> create(String developerId, Long connectionId, String name, String modelPath,
                                      String inputKind, Double minConfidence, Integer timeoutSeconds) {
        return create(developerId, connectionId, name, modelPath, inputKind, null,
                minConfidence, timeoutSeconds);
    }

    /**
     * Creates a tool.
     *
     * @param toolKind what shape of work it does; null keeps the pre-existing
     *                 behaviour of assuming a detector, so callers written
     *                 before tool kinds existed are unaffected
     */
    @Transactional
    public Map<String, Object> create(String developerId, Long connectionId, String name, String modelPath,
                                      String inputKind, io.continuum.tool.ToolKind toolKind,
                                      Double minConfidence, Integer timeoutSeconds) {
        if (name == null || name.isBlank()) {
            throw new SpecialistConnectionService.InvalidConnectionException("A specialist needs a name.");
        }
        if (modelPath == null || modelPath.isBlank()) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "A specialist needs the provider's model path.");
        }
        if (repo.findByDeveloperIdAndName(developerId, name).isPresent()) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "You already have a specialist called '" + name + "'.");
        }
        // Fails here if the connection is not this developer's.
        connections.require(developerId, connectionId);

        SpecialistEntity draft = new SpecialistEntity(developerId, connectionId, name.strip(),
                modelPath.strip(), inputKind,
                minConfidence == null ? 0.30 : minConfidence,
                timeoutSeconds == null ? 20 : timeoutSeconds);
        if (toolKind != null) {
            draft.setToolKind(toolKind);
        }
        return describe(repo.save(draft));
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Long id, Double minConfidence,
                                         Integer timeoutSeconds) {
        SpecialistEntity s = require(developerId, id);
        if (minConfidence != null) {
            s.setMinConfidence(minConfidence);
        }
        if (timeoutSeconds != null) {
            s.setTimeoutSeconds(timeoutSeconds);
        }
        repo.save(s);
        return describe(s);
    }

    /**
     * Sends one real request and records what came back.
     *
     * <p>Uses the caller's sample when they supply one — a developer testing a
     * document classifier needs their own document, not a blank image.
     */
    @Transactional
    public Map<String, Object> probe(String developerId, Long id, Map<String, Object> sample) {
        SpecialistEntity s = require(developerId, id);

        Map<String, Object> input = sample == null || sample.isEmpty()
                ? defaultSampleFor(s.getInputKind())
                : sample;

        if (input == null) {
            // Recorded as a failed probe rather than thrown, so the specialist
            // stays DRAFT and the console can display the reason next to it —
            // the same treatment any other unsuccessful probe receives.
            String msg = sampleRequiredMessage(s.getInputKind());
            s.recordSampleRequired(msg);
            repo.save(s);
            Map<String, Object> needsSample = describe(s);
            needsSample.put("probeParsed", List.of());
            needsSample.put("probeDropped", 0);
            needsSample.put("sampleRequired", true);
            return needsSample;
        }

        SpecialistInvoker.Result r = invoker.invoke(s, input, null);

        String findingsJson;
        try {
            findingsJson = mapper.writeValueAsString(
                    r.evidence().stream().map(io.continuum.tool.Evidence::describe).toList());
        } catch (Exception e) {
            findingsJson = "[]";
        }
        s.recordProbe(r.httpStatus() == null ? 0 : r.httpStatus(), r.latencyMs(),
                r.rawBody(), findingsJson, r.evidence().size() + r.dropped(), r.error());
        repo.save(s);

        Map<String, Object> out = describe(s);
        // The parsed findings are returned alongside the raw body so the console
        // can show both: "here is what your model said, and here is what
        // Continuum understood from it".
        // The full evidence, whatever shape it is. Showing only findings meant
        // an OCR probe displayed an empty result beside a perfectly good body.
        out.put("probeParsed", r.evidence().stream()
                .map(io.continuum.tool.Evidence::describe).toList());
        out.put("probeDropped", r.dropped());
        out.put("probeUnscored", r.unscored());
        out.put("sampleRequired", false);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String developerId) {
        return repo.findByDeveloperIdOrderByNameAsc(developerId).stream().map(this::describe).toList();
    }

    @Transactional(readOnly = true)
    public SpecialistEntity require(String developerId, Long id) {
        return repo.findByIdAndDeveloperId(id, developerId)
                .orElseThrow(() -> new SpecialistConnectionService.InvalidConnectionException(
                        "No such specialist."));
    }

    @Transactional
    public void delete(String developerId, Long id) {
        repo.delete(require(developerId, id));
    }

    /** A minimal payload of the right kind, for probing without a supplied sample. */
    /**
     * A sample good enough to learn a response shape from, or null when there
     * isn't one.
     *
     * <p>Null is a real answer for audio and documents. Continuum has no blank
     * WAV or blank PDF that any real provider would accept, and the previous
     * behaviour — sending {@code {"audioBase64": ""}} for audio, and a 1x1 PNG
     * for anything it did not recognise, documents included — probed those tools
     * with content they could not possibly process. A probe that proves nothing
     * is worse than no probe, because the tool is then marked READY.
     */
    static Map<String, Object> defaultSampleFor(String inputKind) {
        return switch (inputKind == null ? "image" : inputKind) {
            case "text" -> Map.of("text", "The quick brown fox jumps over the lazy dog.");
            case "json" -> Map.of("sample", true);
            case "image" -> Map.of("imageBase64", SAMPLE_IMAGE_BASE64);
            // No universal sample exists. The developer supplies one.
            case "audio", "document" -> null;
            default -> Map.of("imageBase64", SAMPLE_IMAGE_BASE64);
        };
    }

    /** What to tell a developer who must supply their own probe sample. */
    static String sampleRequiredMessage(String inputKind) {
        return "audio".equals(inputKind)
                ? "This tool takes audio, and Continuum has no sample recording that a real "
                        + "transcription service would accept. Supply a short clip — base64 under "
                        + "\"audioBase64\" — so the probe exercises the real path."
                : "This tool takes a document, and Continuum has no sample document that a real "
                        + "extraction service would accept. Supply one — base64 under "
                        + "\"documentBase64\" — so the probe exercises the real path.";
    }

    public Map<String, Object> describe(SpecialistEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("connectionId", s.getConnectionId());
        m.put("modelPath", s.getModelPath());
        m.put("inputKind", s.getInputKind());
        m.put("minConfidence", s.getMinConfidence());
        m.put("timeoutSeconds", s.getTimeoutSeconds());
        m.put("status", s.getStatus().name());
        m.put("toolKind", s.getToolKind().name());
        m.put("toolKindLabel", s.getToolKind().label());
        m.put("scored", s.getToolKind().isScored());
        m.put("probeStatus", s.getProbeStatus());
        m.put("probeMs", s.getProbeMs());
        m.put("probeError", s.getProbeError());
        m.put("probedAt", s.getProbedAt());
        m.put("probeResponse", s.getProbeResponse());
        try {
            m.put("probeFindings", s.getProbeFindings() == null ? List.of()
                    : mapper.readValue(s.getProbeFindings(), List.class));
        } catch (Exception e) {
            m.put("probeFindings", List.of());
        }
        m.put("createdAt", s.getCreatedAt());
        return m;
    }
}
