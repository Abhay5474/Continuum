package io.continuum.hedging;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;

/**
 * Calls exactly one provider. Abstracted so the hedging executor can be unit
 * tested with deterministic fakes while production wires it to the real
 * {@link io.continuum.provider.ProviderRouter}.
 */
public interface ProviderCaller {
    LlmResponse call(String provider, LlmRequest request) throws Exception;
}
