package io.continuum.api;

import io.continuum.api.dto.Dtos.*;
import io.continuum.core.outbox.DeliveryRecorder;
import io.continuum.core.workflow.WorkflowRegistry;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.provider.ProviderRouter;
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

    @GetMapping("/stats")
    public StatsView stats() {
        long running = instances.countByStatus(WorkflowStatus.RUNNING);
        long completed = instances.countByStatus(WorkflowStatus.COMPLETED);
        long failed = instances.countByStatus(WorkflowStatus.FAILED);
        return new StatsView(running + completed + failed, running, completed, failed,
                deliveries.totalDeliveries(), deliveries.duplicates());
    }

    @GetMapping("/costs")
    public CostReport costs() {
        return query.costReport();
    }

    @GetMapping("/deliveries")
    public List<DeliveryRecorder.Delivery> deliveries() {
        return deliveries.deliveries();
    }
}
