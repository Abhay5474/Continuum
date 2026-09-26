package io.continuum.api;

import io.continuum.api.dto.Dtos.*;
import io.continuum.core.outbox.DeliveryRecorder;
import io.continuum.core.workflow.WorkflowRegistry;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.portal.RequestScope;
import io.continuum.provider.ProviderRouter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final WorkflowRegistry workflows;
    private final ProviderRouter router;
    private final WorkflowInstanceRepository instances;
    private final DeliveryRecorder deliveries;
    private final io.continuum.persistence.repository.OutboxRepository outbox;
    private final WorkflowQueryService query;
    // Optional: absent when this process runs with workers disabled.
    private final java.util.Optional<io.continuum.core.engine.ActivityWorker> activityWorker;

    public MetaController(WorkflowRegistry workflows, ProviderRouter router,
                          WorkflowInstanceRepository instances, DeliveryRecorder deliveries,
                          WorkflowQueryService query,
                          java.util.Optional<io.continuum.core.engine.ActivityWorker> activityWorker,
                          io.continuum.persistence.repository.OutboxRepository outbox) {
        this.workflows = workflows;
        this.router = router;
        this.instances = instances;
        this.deliveries = deliveries;
        this.query = query;
        this.activityWorker = activityWorker;
        this.outbox = outbox;
    }

    @GetMapping("/meta")
    public MetaView meta() {
        return new MetaView(workflows.types().stream().sorted().toList(), router.availableChain());
    }

    /**
     * Worker occupancy.
     *
     * <p>Activities used to execute inline on Spring's single scheduler thread,
     * so the engine's real concurrency was one regardless of how many were
     * queued. They now run on a bounded pool; this reports how much of it is
     * busy, which is the difference made visible.
     */
    @GetMapping("/engine/capacity")
    public java.util.Map<String, Object> capacity() {
        return activityWorker
                .map(w -> java.util.Map.<String, Object>of(
                        "inFlight", w.inFlight(),
                        "capacity", w.capacity(),
                        "saturation", w.capacity() == 0 ? 0.0 : (double) w.inFlight() / w.capacity(),
                        "workersEnabled", true))
                .orElse(java.util.Map.of("inFlight", 0, "capacity", 0, "saturation", 0.0,
                        "workersEnabled", false));
    }

    /** Workflow counters for the signed-in developer (engine-wide for an operator). */
    @GetMapping("/stats")
    public StatsView stats(HttpServletRequest http) {
        String dev = RequestScope.developerId(http);
        long running, completed, failed, cancelled;
        String prefix = io.continuum.api.WorkflowQueryService.CANCELLED_PREFIX;
        if (dev == null) {
            running = instances.countByStatus(WorkflowStatus.RUNNING);
            completed = instances.countByStatus(WorkflowStatus.COMPLETED);
            failed = instances.countByStatus(WorkflowStatus.FAILED);
            cancelled = instances.countByStatusAndErrorStartingWith(WorkflowStatus.FAILED, prefix);
        } else {
            running = instances.countByDeveloperIdAndStatus(dev, WorkflowStatus.RUNNING);
            completed = instances.countByDeveloperIdAndStatus(dev, WorkflowStatus.COMPLETED);
            failed = instances.countByDeveloperIdAndStatus(dev, WorkflowStatus.FAILED);
            cancelled = instances.countByDeveloperIdAndStatusAndErrorStartingWith(dev, WorkflowStatus.FAILED, prefix);
        }
        if (dev == null) {
            return new StatsView(running + completed + failed, running, completed, failed,
                    deliveries.totalDeliveries(), deliveries.duplicates(), cancelled);
        }
        // A developer's own deliveries. The recorder is engine-wide, and its
        // duplicate list names other tenants' idempotency keys (which carry
        // their run ids), so it is filtered to runs this account owns.
        java.util.Map<String, String> runOfKey = new java.util.HashMap<>();
        for (DeliveryRecorder.Delivery d : deliveries.deliveries()) {
            if (d.workflowId() != null) {
                runOfKey.put(d.idempotencyKey(), d.workflowId());
            }
        }
        List<String> dups = deliveries.duplicates();
        java.util.Set<String> mine = ownedBy(dev, dups.stream().map(runOfKey::get).toList());
        List<String> ownDups = dups.stream().filter(k -> mine.contains(runOfKey.get(k))).toList();
        long sent = outbox.countSentForDeveloper(dev);
        return new StatsView(running + completed + failed, running, completed, failed,
                (int) Math.min(Integer.MAX_VALUE, sent), ownDups, cancelled);
    }

    @GetMapping("/costs")
    public CostReport costs(HttpServletRequest http) {
        String dev = RequestScope.developerId(http);
        return dev == null ? query.costReport() : query.costReportForDeveloper(dev);
    }

    /**
     * The outbox delivery log, newest first. The operator sees the whole
     * engine's; a developer sees the deliveries made by their own runs. It used
     * to be anonymous, then operator-only because records carried no tenant;
     * each now carries the run it came from, so it can be scoped instead.
     */
    @GetMapping("/deliveries")
    public ResponseEntity<List<DeliveryRecorder.Delivery>> deliveries(HttpServletRequest http) {
        List<DeliveryRecorder.Delivery> all = new java.util.ArrayList<>(deliveries.deliveries());
        java.util.Collections.reverse(all);
        if (RequestScope.isOperator(http) && RequestScope.developerId(http) == null) {
            return ResponseEntity.ok(all);
        }
        String dev = RequestScope.developerId(http);
        if (dev == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        java.util.Set<String> mine = ownedBy(dev, all.stream().map(DeliveryRecorder.Delivery::workflowId).toList());
        return ResponseEntity.ok(all.stream().filter(d -> d.workflowId() != null && mine.contains(d.workflowId())).toList());
    }

    /** Of these run ids, the ones that belong to {@code dev}. One query, whatever the count. */
    private java.util.Set<String> ownedBy(String dev, java.util.Collection<String> workflowIds) {
        java.util.Set<String> ids = new java.util.HashSet<>(workflowIds);
        ids.remove(null);
        java.util.Set<String> mine = new java.util.HashSet<>();
        if (ids.isEmpty()) {
            return mine;
        }
        instances.findAllById(ids).forEach(w -> {
            if (dev.equals(w.getDeveloperId())) {
                mine.add(w.getWorkflowId());
            }
        });
        return mine;
    }
}
