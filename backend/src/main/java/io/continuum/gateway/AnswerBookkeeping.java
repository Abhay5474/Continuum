package io.continuum.gateway;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.admission.AdmissionService;
import io.continuum.admission.CostAdmissionService;
import io.continuum.admission.CostAwareLimiter;
import io.continuum.admission.Criticality;
import io.continuum.scheduling.DeadlineScheduler;
import io.continuum.scheduling.SchedulerService;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.LlmResponse;
import io.continuum.registry.ModelRegistryService;
import io.continuum.routing.ProviderSelectionEngine;
import io.continuum.routing.RoutingMode;
import io.continuum.routing.RoutingPolicy;
import io.continuum.routing.SelectionResult;
import io.continuum.routing.TaskComplexityEstimator;
import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.continuum.gateway.GatewaySupport.*;

/**
 * What learning a served (or failed) request feeds: the Autopilot's request
 * labels and the contextual bandit. Neither may ever fail the request.
 */
@org.springframework.stereotype.Component
public class AnswerBookkeeping {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnswerBookkeeping.class);

    private final io.continuum.persistence.repository.AutopilotRequestLabelRepository autopilotLabels;
    private final io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit;

    public AnswerBookkeeping(io.continuum.persistence.repository.AutopilotRequestLabelRepository autopilotLabels, io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit) {
        this.autopilotLabels = autopilotLabels;
        this.contextualBandit = contextualBandit;
    }
    public void labelForAutopilot(java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> ap,
                                   Long gatewayRequestId, String developerId, boolean success, long latencyMs, double cost) {
        if (ap.isEmpty()) {
            return;
        }
        try {
            autopilotLabels.save(new io.continuum.persistence.entity.AutopilotRequestLabelEntity(
                    gatewayRequestId, developerId, ap.get().bundleId(), ap.get().canary(), success, latencyMs, cost));
        } catch (Exception ignored) {
            // Labeling must never break a request.
        }
    }
    /** Record a routing outcome into the contextual bandit; never affects the request. */
    public void recordBandit(double complexity, String provider, boolean success, long latencyMs, double cost) {
        try {
            contextualBandit.observe(complexity, provider, success, latencyMs, cost);
        } catch (Exception ignored) {
            // Learning must never break a request.
        }
    }
}
