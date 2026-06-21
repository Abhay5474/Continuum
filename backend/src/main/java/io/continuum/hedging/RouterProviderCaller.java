package io.continuum.hedging;

import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/** Production {@link ProviderCaller}: calls a single provider through the router (records metrics). */
@Component
public class RouterProviderCaller implements ProviderCaller {

    private final ProviderRouter router;

    public RouterProviderCaller(ProviderRouter router) {
        this.router = router;
    }

    @Override
    public LlmResponse call(String provider, LlmRequest request) {
        return router.complete(request, List.of(provider));
    }
}
