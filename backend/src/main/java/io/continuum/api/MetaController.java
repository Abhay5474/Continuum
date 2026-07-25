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

    public MetaController(WorkflowRegistry workflows, ProviderRouter router,
                          WorkflowInstanceRepository instances, DeliveryRecorder deliveries,
                          WorkflowQueryService query) {
        this.workflows = workflows;
        this.router = router;
        this.instances = instances;
        this.deliveries = deliveries;
        this.query = query;
    }

    @GetMapping("/meta")
    public MetaView meta() {
        return new MetaView(workflows.types().stream().sorted().toList(), router.availableChain());
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
