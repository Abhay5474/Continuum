package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.common.Json;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.persistence.entity.WorkflowDefinitionEntity;
import io.continuum.persistence.repository.WorkflowDefinitionRepository;
import io.continuum.saga.SagaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes and runs customer-defined workflows.
 *
 * <p>Publishing validates the spec and appends a new version — definitions are
 * never mutated in place, because an in-flight execution must be able to replay
 * against exactly the graph it started with.
 */
@Service
public class DefinitionService {

    private final WorkflowDefinitionRepository repo;
    private final WorkflowEngine engine;
    private final ObjectMapper mapper;
    private final Json json;
    private final SagaService saga;

    public DefinitionService(WorkflowDefinitionRepository repo, WorkflowEngine engine,
                             ObjectMapper mapper, Json json, SagaService saga) {
        this.repo = repo;
        this.engine = engine;
        this.mapper = mapper;
        this.json = json;
        this.saga = saga;
    }

    /** Validates and stores a new version of {@code name}. */
    @Transactional
    public WorkflowDefinitionEntity publish(String developerId, String name, Object spec) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,120}")) {
            throw new WorkflowSpec.InvalidSpecException(
                    "Workflow name must be 1-120 chars of letters, digits, _ or -.");
        }
        WorkflowSpec parsed = mapper.convertValue(spec, WorkflowSpec.class);
        parsed.validate();

        int next = repo.findFirstByDeveloperIdAndNameOrderByVersionDesc(developerId, name)
                .map(d -> d.getVersion() + 1).orElse(1);
        return repo.save(new WorkflowDefinitionEntity(developerId, name, next, json.write(parsed)));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String developerId) {
        return repo.findByDeveloperIdOrderByNameAscVersionDesc(developerId).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String developerId, String name, Integer version) {
        WorkflowDefinitionEntity d = resolve(developerId, name, version);
        Map<String, Object> out = summary(d);
        out.put("spec", json.read(d.getSpecJson(), Object.class));
        return out;
    }

    @Transactional
    public void delete(String developerId, String name) {
        repo.deleteByDeveloperIdAndName(developerId, name);
    }

    /**
     * Starts a durable run of a definition. The spec is embedded in the run's
     * input, so the execution is pinned to the version it started with.
     */
    @Transactional
    public Map<String, Object> run(String developerId, String name, Integer version,
                                   Map<String, Object> input, String requestedId) {
        WorkflowDefinitionEntity d = resolve(developerId, name, version);
        // Pinned at start. A run must replay the way it began, so toggling the
        // setting mid-flight cannot change whether a rollback happens.
        DeclarativeWorkflow.Run run = new DeclarativeWorkflow.Run(
                d.getName(), d.getVersion(), json.read(d.getSpecJson(), Object.class), input,
                saga.enabled(developerId), developerId);

        String workflowId = engine.startWorkflow(DeclarativeWorkflow.TYPE, json.write(run), requestedId, developerId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        out.put("definition", d.getName());
        out.put("version", d.getVersion());
        out.put("status", "RUNNING");
        return out;
    }

    private WorkflowDefinitionEntity resolve(String developerId, String name, Integer version) {
        return (version == null
                ? repo.findFirstByDeveloperIdAndNameOrderByVersionDesc(developerId, name)
                : repo.findByDeveloperIdAndNameAndVersion(developerId, name, version))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such workflow definition: " + name + (version == null ? "" : " v" + version)));
    }

    private Map<String, Object> summary(WorkflowDefinitionEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.getName());
        m.put("version", d.getVersion());
        m.put("createdAt", d.getCreatedAt());
        try {
            WorkflowSpec s = mapper.readValue(d.getSpecJson(), WorkflowSpec.class);
            m.put("description", s.getDescription());
            m.put("steps", s.getSteps().size());
        } catch (Exception e) {
            m.put("steps", 0);
        }
        return m;
    }
}
