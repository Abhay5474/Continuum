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
 * Applies armed AI-level faults to a provider response, for the tenant that
 * armed them. Every stage that calls a provider passes its answer through here,
 * so a drill reaches the path an external application actually uses.
 */
@org.springframework.stereotype.Component
public class ResponseChaos {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResponseChaos.class);

    private final io.continuum.aichaos.AiChaosEngine aiChaos;

    public ResponseChaos(io.continuum.aichaos.AiChaosEngine aiChaos) {
        this.aiChaos = aiChaos;
    }
    /** True while this tenant has AI chaos armed: its answers are corrupted on purpose. */
    public boolean active(String developerId) {
        return developerId != null && aiChaos.isActive(developerId);
    }

    /**
     * Applies armed AI-level faults to a provider response.
     *
     * <p>Chaos is scoped to the tenant that armed it, so this is safe to run
     * against production traffic — and it is what makes a hallucination drill
     * visible on the path an external application actually uses.
     */
    public LlmResponse apply(String developerId, LlmResponse response) {
        if (developerId == null || !aiChaos.isActive(developerId)) {
            return response;
        }
        try {
            return aiChaos.applyToResponse(response, "gateway:" + developerId, null);
        } catch (Exception e) {
            log.warn("AI chaos injection failed for {}; returning the response untouched: {}",
                    developerId, e.getMessage());
            return response;
        }
    }
}
