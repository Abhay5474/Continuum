package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.TraceStepEntity;
import io.continuum.persistence.repository.TraceStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records the chain behind an answer, so it can be shown rather than described.
 *
 * <p>Without this the specialist layer's contribution is invisible. An
 * application that sends an image and gets first-aid advice cannot tell that a
 * second model was consulted, that its findings were turned into structured
 * context, or that the confidence was checked before the advice was written —
 * and if the user cannot see it, the work may as well not have happened.
 *
 * <p>Written from the first specialist rather than retrofitted later, because a
 * trace assembled after the fact is a reconstruction: it can only show what was
 * separately logged, in whatever order the logs happen to allow.
 *
 * <p>Every method swallows its failures. A trace is a description of work, and
 * losing the description must never lose the work.
 */
@Service
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final TraceStepRepository repo;
    private final ObjectMapper mapper;

    /** Per-trace step counter, so ordinals are dense and monotonic. */
    private final Map<String, AtomicInteger> ordinals = new ConcurrentHashMap<>();

    public TraceRecorder(TraceStepRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** A new trace id. Returned to the caller so it can fetch the chain later. */
    public String newTrace() {
        return java.util.UUID.randomUUID().toString();
    }

    @Transactional
    public void step(String traceId, String developerId, TraceStepEntity.Kind kind, String label,
                     Object detail, String status, Double confidence, double cost, long latencyMs) {
        if (traceId == null || developerId == null) {
            return;
        }
        try {
            int ordinal = ordinals.computeIfAbsent(traceId, k -> new AtomicInteger()).getAndIncrement();
            repo.save(new TraceStepEntity(traceId, developerId, ordinal, kind, label,
                    detail == null ? null : mapper.writeValueAsString(detail),
                    status, confidence, cost, latencyMs));
        } catch (Exception e) {
            log.debug("Could not record trace step: {}", e.getMessage());
        }
    }

    /**
     * The chain for one trace, shaped for display.
     *
     * <p>Scoped by developer as well as trace id: a trace id is a UUID, but
     * relying on unguessability for access control is how tenant data leaks.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(String developerId, String traceId) {
        List<TraceStepEntity> steps = repo.findByTraceIdAndDeveloperIdOrderByOrdinalAsc(traceId, developerId);
        List<Map<String, Object>> out = new ArrayList<>();
        double cost = 0;
        long latency = 0;
        for (TraceStepEntity s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ordinal", s.getOrdinal());
            m.put("kind", s.getKind().name());
            m.put("label", s.getLabel());
            m.put("status", s.getStatus());
            m.put("confidence", s.getConfidence());
            m.put("cost", s.getCost());
            m.put("latencyMs", s.getLatencyMs());
            m.put("at", s.getCreatedAt());
            try {
                m.put("detail", s.getDetail() == null ? null
                        : mapper.readValue(s.getDetail(), Object.class));
            } catch (Exception e) {
                m.put("detail", s.getDetail());
            }
            out.add(m);
            cost += s.getCost();
            latency += s.getLatencyMs();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("steps", out);
        result.put("stepCount", out.size());
        result.put("totalCost", cost);
        result.put("totalLatencyMs", latency);
        result.put("found", !out.isEmpty());
        return result;
    }

    @Transactional(readOnly = true)
    public List<String> recent(String developerId, int limit) {
        return repo.recentTraceIds(developerId, PageRequest.of(0, Math.max(1, Math.min(100, limit))));
    }

    @Transactional
    public void clear(String developerId) {
        repo.deleteByDeveloperId(developerId);
    }

    /** Releases the counter for a finished trace, so the map does not grow forever. */
    public void finish(String traceId) {
        if (traceId != null) {
            ordinals.remove(traceId);
        }
    }
}
