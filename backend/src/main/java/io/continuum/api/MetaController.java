package io.continuum.api;

import io.continuum.api.dto.Dtos.*;
import io.continuum.core.outbox.DeliveryRecorder;
import io.continuum.core.workflow.WorkflowRegistry;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.portal.RequestScope;
import io.continuum.provider.ProviderRouter;
import jakarta.servlet.http.HttpServletRequest;
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
    private final WorkflowQueryService query;
    // Optional: absent when this process runs with workers disabled.
    private final java.util.Optional<io.continuum.core.engine.ActivityWorker> activityWorker;

    public MetaController(WorkflowRegistry workflows, ProviderRouter router,
                          WorkflowInstanceRepository instances, DeliveryRecorder deliveries,
                          WorkflowQueryService query,
                          java.util.Optional<io.continuum.core.engine.ActivityWorker> activityWorker) {
        this.workflows = workflows;
        this.router = router;
        this.instances = instances;
        this.deliveries = deliveries;
        this.query = query;
        this.activityWorker = activityWorker;
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
        long running, completed, failed;
        if (dev == null) {
            running = instances.countByStatus(WorkflowStatus.RUNNING);
            completed = instances.countByStatus(WorkflowStatus.COMPLETED);
            failed = instances.countByStatus(WorkflowStatus.FAILED);
        } else {
            running = instances.countByDeveloperIdAndStatus(dev, WorkflowStatus.RUNNING);
            completed = instances.countByDeveloperIdAndStatus(dev, WorkflowStatus.COMPLETED);
            failed = instances.countByDeveloperIdAndStatus(dev, WorkflowStatus.FAILED);
        }
        return new StatsView(running + completed + failed, running, completed, failed,
                deliveries.totalDeliveries(), deliveries.duplicates());
    }

    @GetMapping("/costs")
    public CostReport costs(HttpServletRequest http) {
        String dev = RequestScope.developerId(http);
        return dev == null ? query.costReport() : query.costReportForDeveloper(dev);
    }

    @GetMapping("/deliveries")
    public List<DeliveryRecorder.Delivery> deliveries() {
        return deliveries.deliveries();
    }
}
