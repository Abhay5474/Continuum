package io.continuum.provider;

import io.continuum.config.LlmProperties;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.vault.CredentialVaultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Work Continuum does for an account outside a chat request (the verification
 * engine's agents) reaches the providers with the keys the account saved, when
 * the server was started without any.
 */
class OwnProviderKeysTest {

    /** A provider the server has no key for: it answers only with a key handed to it. */
    static final class Keyless implements LlmProvider {
        final String name;
        final List<String> keysUsed = new ArrayList<>();

        Keyless(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return false;
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
            keysUsed.add(key);
            return new LlmResponse("ok", List.of(), 1, 1, name, "m", "stop");
        }
    }

    @SuppressWarnings("unchecked")
    private static ProviderRouter router(LlmProvider... providers) {
        LlmProperties props = new LlmProperties();
        props.setFailoverOrder(List.of("gemini", "groq"));
        return new ProviderRouter(List.of(providers), props, mock(ObjectProvider.class));
    }

    @Test
    void aSavedKeyPutsItsProviderInTheChainAndIsUsed() {
        Keyless gemini = new Keyless("gemini");
        Keyless groq = new Keyless("groq");
        ProviderRouter router = router(gemini, groq);
        CredentialVaultService vault = mock(CredentialVaultService.class);
        when(vault.listProviders("dev")).thenReturn(List.of(
                new CredentialVaultService.CredentialInfo("groq", "t", "t"),
                // A specialist's credential lives in the same vault and is not a model key.
                new CredentialVaultService.CredentialInfo("specialist:7", "t", "t")));
        when(vault.decrypt("dev", "groq")).thenReturn(Optional.of("gsk_saved"));
        when(vault.decrypt("dev", "specialist:7")).thenReturn(Optional.of("token"));

        Map<String, String> keys = new OwnProviderKeys(vault, router).forDeveloper("dev");

        assertThat(keys).containsExactly(Map.entry("groq", "gsk_saved"));
        assertThat(router.availableChain()).isEmpty();
        assertThat(router.chainFor(keys)).containsExactly("groq");
        router.complete(LlmRequest.of(List.of(Message.user("hi"))), router.chainFor(keys), keys);
        assertThat(groq.keysUsed).containsExactly("gsk_saved");
        assertThat(gemini.keysUsed).isEmpty();
    }

    @Test
    void noAccountMeansNoKeys() {
        ProviderRouter router = router(new Keyless("groq"));
        assertThat(new OwnProviderKeys(mock(CredentialVaultService.class), router).forDeveloper(null)).isEmpty();
    }
}
