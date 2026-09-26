package io.continuum.provider;

import io.continuum.config.LlmProperties;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.registry.catalog.ModelCatalogService;
import io.continuum.registry.catalog.ModelResolver;
import io.continuum.routing.ProviderMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When a provider says the model is gone, the router tells the catalogue and
 * retries once on the replacement — and only for the platform's key.
 */
class ProviderRouterModelGoneTest {

    /** Groq that has retired one model and serves another. */
    static final class Retiring implements LlmProvider {
        final List<String> calls = new ArrayList<>();

        @Override
        public String name() {
            return "groq";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public double estimateCost(String model, int p, int c) {
            return 0;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return complete(request, null);
        }

        @Override
        public LlmResponse complete(LlmRequest request, String key) {
            calls.add(request.model());
            if ("llama-3.3-70b-versatile".equals(request.model())) {
                throw new ModelUnavailableException("groq", request.model(), ModelUnavailableException.Reason.GONE,
                        new RuntimeException("HTTP 404: model_not_found"));
            }
            return new LlmResponse("ok", List.of(), 1, 1, "groq", request.model(), "stop");
        }
    }

    private final Retiring groq = new Retiring();
    private final ModelCatalogService catalogue = mock(ModelCatalogService.class);
    private final ModelResolver resolver = mock(ModelResolver.class);
    private final ProviderRouter router = router();

    @SuppressWarnings("unchecked")
    private ProviderRouter router() {
        LlmProperties props = new LlmProperties();
        props.setFailoverOrder(List.of("groq"));
        ObjectProvider<ProviderMetrics> metrics = mock(ObjectProvider.class);
        ProviderRouter r = new ProviderRouter(List.of(groq), props, metrics);
        ObjectProvider<ModelCatalogService> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(catalogue);
        ObjectProvider<ModelResolver> rp = mock(ObjectProvider.class);
        when(rp.getIfAvailable()).thenReturn(resolver);
        ReflectionTestUtils.setField(r, "catalogueProvider", cp);
        ReflectionTestUtils.setField(r, "resolverProvider", rp);
        when(resolver.defaultFor("groq")).thenReturn("openai/gpt-oss-120b");
        return r;
    }

    private static LlmRequest ask(String model) {
        return new LlmRequest(model, List.of(Message.user("hi")), 16, 0.0);
    }

    @Test
    void theRetirementIsReportedAndTheRequestIsAnsweredByTheReplacement() {
        LlmResponse r = router.complete(ask("llama-3.3-70b-versatile"), List.of("groq"));

        assertThat(r.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(groq.calls).containsExactly("llama-3.3-70b-versatile", "openai/gpt-oss-120b");
        verify(catalogue).reportUnavailable("groq", "llama-3.3-70b-versatile", true, "HTTP 404: model_not_found");
    }

    @Test
    void aDevelopersOwnKeyNeitherReportsNorRetries() {
        assertThatThrownBy(() -> router.complete(ask("llama-3.3-70b-versatile"), List.of("groq"),
                Map.of("groq", "their-key"))).isInstanceOf(RuntimeException.class);

        assertThat(groq.calls).containsExactly("llama-3.3-70b-versatile");
        verify(catalogue, never()).reportUnavailable(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    }

    @Test
    void neverMoreThanOneRetry() {
        when(resolver.defaultFor("groq")).thenReturn("llama-3.3-70b-versatile");

        assertThatThrownBy(() -> router.complete(ask("llama-3.3-70b-versatile"), List.of("groq")))
                .isInstanceOf(RuntimeException.class);

        assertThat(groq.calls).containsExactly("llama-3.3-70b-versatile");
    }
}
