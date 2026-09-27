package io.continuum.testsupport;

import io.continuum.chaos.ChaosMonkey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The test LLM, for tests that run the whole application. The product has no
 * built-in model: a deployment answers with Groq or Gemini, or says that none
 * is set up. Tests need a provider that answers without a network or a key.
 */
@TestConfiguration
public class FakeProviders {

    @Bean
    FakeLlmProvider fakeLlmProvider(ChaosMonkey chaos) {
        return new FakeLlmProvider(chaos);
    }

    @Bean
    FakeModelDiscovery fakeModelDiscovery() {
        return new FakeModelDiscovery();
    }
}
