package io.continuum.provenance;

import io.continuum.persistence.entity.DecisionRecordEntity;
import io.continuum.persistence.entity.ProvenanceSettingEntity;
import io.continuum.persistence.repository.DecisionRecordRepository;
import io.continuum.persistence.repository.ProvenanceSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records why Continuum did what it did, as data.
 *
 * <p>Every subsystem on the request path already explains itself, and every
 * explanation ends up concatenated into one string on the response. That string
 * is fine for a person reading a single answer and worthless for everything else:
 * you cannot aggregate it, alert on it, bill against it, or answer <i>"how often
 * did the cascade escalate this week and what did it cost me?"</i>
 *
 * <p>This records the same decisions as rows — one per decision, not one blob
 * per request, because the questions worth asking are aggregations over
 * decisions.
 *
 * <p>Exported in OpenTelemetry's shape, using the GenAI semantic conventions
 * where they exist. <b>An observability feature that can only be read inside the
 * product it observes has solved the easy half of the problem</b>; the point is
 * for this to land in the tracing tool a developer already runs.
 *
 * <p>Off by default. Recording is cheap but not free, and a request path is the
 * wrong place to add writes nobody asked for.
 */
@Service
public class ProvenanceService {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceService.class);

    private final DecisionRecordRepository records;
    private final ProvenanceSettingRepository settings;

    public ProvenanceService(DecisionRecordRepository records,
                             ProvenanceSettingRepository settings) {
        this.records = records;
        this.settings = settings;
    }

    /**
     * One request's decisions, accumulated in memory and written once at the end.
     *
     * <p>Buffered rather than written as they happen: a request makes a dozen
     * decisions, and a dozen synchronous inserts on the hot path would make the
     * observability cost more than the thing observed.
     */
    public final class Recording {
        private final String developerId;
        private final String requestId = UUID.randomUUID().toString();
        private final List<Decision> decisions = new ArrayList<>();

        private Recording(String developerId) {
            this.developerId = developerId;
        }

        public String requestId() {
            return requestId;
        }

        public void add(Decision d) {
            if (d != null) {
                decisions.add(d);
            }
        }

        public void add(Decision.Stage stage, String choice, String reason) {
            add(Decision.of(stage, choice, reason));
        }

        public List<Decision> decisions() {
            return List.copyOf(decisions);
        }

        /** Writes the graph. Never throws — losing the record must not lose the answer. */
        public void commit() {
            if (decisions.isEmpty()) {
                return;
            }
            try {
                List<DecisionRecordEntity> rows = new ArrayList<>();
                for (int i = 0; i < decisions.size(); i++) {
                    Decision d = decisions.get(i);
                    rows.add(new DecisionRecordEntity(developerId, requestId, i, d.stage().name(),
                            d.choice(), d.reason(),
                            d.alternatives().isEmpty() ? null : String.join(", ", d.alternatives()),
                            d.costDelta(), d.latencyMs()));
                }
                records.saveAll(rows);
            } catch (RuntimeException e) {
                log.debug("Could not record provenance: {}", e.getMessage());
            }
        }
    }

    /** A recording, or null when the tenant has not turned this on. */
    @Transactional(readOnly = true)
    public Recording start(String developerId) {
        return enabled(developerId) ? new Recording(developerId) : null;
    }

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        try {
            return settings.findById(developerId)
                    .map(ProvenanceSettingEntity::isEnabled).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled) {
        ProvenanceSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new ProvenanceSettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        settings.save(cfg);
        return status(developerId);
    }

    // --- reading --------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        List<DecisionRecordEntity> recent = records.findByDeveloperIdOrderByCreatedAtDesc(
                developerId, PageRequest.of(0, 500));
        Map<String, Long> byStage = new LinkedHashMap<>();
        Map<String, Double> costByStage = new LinkedHashMap<>();
        for (DecisionRecordEntity r : recent) {
            byStage.merge(r.getStage(), 1L, Long::sum);
            costByStage.merge(r.getStage(), r.getCostDelta(), Double::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled(developerId));
        out.put("decisions", recent.size());
        out.put("requests", records.recentRequests(developerId, PageRequest.of(0, 500)).size());
        out.put("byStage", byStage);
        out.put("costByStage", costByStage);
        return out;
    }

    @Transactional(readOnly = true)
    public List<String> requests(String developerId, int limit) {
        return records.recentRequests(developerId,
                PageRequest.of(0, Math.max(1, Math.min(200, limit))));
    }

    /** One request's decisions, in order. */
    @Transactional(readOnly = true)
    public Map<String, Object> graph(String developerId, String requestId) {
        List<DecisionRecordEntity> rows =
                records.findByRequestIdAndDeveloperIdOrderBySeqAsc(requestId, developerId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        double cost = 0;
        long latency = 0;
        for (DecisionRecordEntity r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.getSeq());
            m.put("stage", r.getStage());
            m.put("choice", r.getChoice());
            m.put("reason", r.getReason());
            m.put("alternatives", r.getAlternatives());
            m.put("costDelta", r.getCostDelta());
            m.put("latencyMs", r.getLatencyMs());
            m.put("at", r.getCreatedAt());
            nodes.add(m);
            cost += r.getCostDelta();
            latency += r.getLatencyMs();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("decisions", nodes);
        out.put("totalCost", cost);
        out.put("totalLatencyMs", latency);
        out.put("found", !nodes.isEmpty());
        return out;
    }

    /**
     * One request as OpenTelemetry spans.
     *
     * <p>Deliberately the same data as {@link #graph}, in the shape a collector
     * expects. Two representations of one truth, not two truths.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> spans(String developerId, String requestId) {
        List<DecisionRecordEntity> rows =
                records.findByRequestIdAndDeveloperIdOrderBySeqAsc(requestId, developerId);
        List<Map<String, Object>> spans = new ArrayList<>();
        for (DecisionRecordEntity r : rows) {
            Decision d = new Decision(Decision.Stage.valueOf(r.getStage()), r.getChoice(),
                    r.getReason(),
                    r.getAlternatives() == null ? List.of() : List.of(r.getAlternatives().split(", ")),
                    r.getCostDelta(), r.getLatencyMs());
            Map<String, Object> span = new LinkedHashMap<>(d.asSpan());
            span.put("traceId", requestId);
            span.put("spanId", requestId + "-" + r.getSeq());
            spans.add(span);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resourceSpans", List.of(Map.of(
                "resource", Map.of("attributes", Map.of("service.name", "continuum")),
                "scopeSpans", List.of(Map.of("spans", spans)))));
        return out;
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        records.deleteByDeveloperId(developerId);
        return status(developerId);
    }
}
