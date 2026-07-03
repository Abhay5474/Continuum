package io.continuum.autopilot.engine;

import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.registry.ModelRegistryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a candidate policy bundle before it is allowed anywhere near traffic.
 * Enforces bounded, safe values and checks that every referenced provider has an
 * ACTIVE registered model (capability/golden check). A failing candidate is never
 * promoted.
 */
@Component
public class PolicyVerifier {

    private final ModelRegistryService registry;

    public PolicyVerifier(ModelRegistryService registry) {
        this.registry = registry;
    }

    public record VerificationResult(boolean passed, List<String> passedChecks, List<String> failures) {
    }

    public VerificationResult verify(PolicyBundle b, DeveloperProfile profile) {
        List<String> ok = new ArrayList<>();
        List<String> fail = new ArrayList<>();

        check(!b.providerOrder().isEmpty(), "provider order is non-empty", ok, fail);

        // Safety: only providers the developer allowed may appear.
        List<String> allowed = profile.allowedProviders();
        if (allowed != null && !allowed.isEmpty()) {
            boolean subset = allowed.containsAll(b.providerOrder());
            check(subset, "all providers are within the developer's allow-list", ok, fail);
        }

        // Golden/capability: every provider must have an ACTIVE registered model.
        for (String p : b.providerOrder()) {
            boolean hasActive = !registry.activeFor(p).isEmpty();
            check(hasActive, "provider '" + p + "' has an ACTIVE model", ok, fail);
        }

        check(b.costCapUsd() > 0, "cost cap is positive", ok, fail);
        check(b.latencyCapMs() > 0, "latency cap is positive", ok, fail);
        check(b.semanticDriftThreshold() > 0 && b.semanticDriftThreshold() <= 1,
                "drift threshold in (0,1]", ok, fail);
        check(b.verificationPassThreshold() >= 0 && b.verificationPassThreshold() <= 1,
                "verification threshold in [0,1]", ok, fail);
        check(b.maxRetries() >= 0 && b.maxRetries() <= 10, "retries in [0,10]", ok, fail);
        check(b.hedgeThresholdMs() >= 0, "hedge threshold non-negative", ok, fail);
        check(b.hedgeMaxParallel() >= 1 && b.hedgeMaxParallel() <= 5, "hedge fan-out in [1,5]", ok, fail);
        check(b.canaryPercentage() >= 0 && b.canaryPercentage() <= 100, "canary % in [0,100]", ok, fail);
        check(b.timeoutSeconds() >= 1 && b.timeoutSeconds() <= 300, "timeout in [1,300]s", ok, fail);

        return new VerificationResult(fail.isEmpty(), ok, fail);
    }

    private void check(boolean condition, String label, List<String> ok, List<String> fail) {
        if (condition) {
            ok.add(label);
        } else {
            fail.add(label);
        }
    }
}
