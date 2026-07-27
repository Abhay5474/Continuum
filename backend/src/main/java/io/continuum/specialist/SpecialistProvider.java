package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;

import java.util.List;
import java.util.Map;

/**
 * One kind of third-party model provider.
 *
 * <p>The point of this interface is that Roboflow is the <em>first</em> adapter
 * rather than the design. A specialist provider only has to answer four
 * questions: where does it live, how is the credential presented, how is a call
 * to one of its models shaped, and how do its results map onto Continuum's
 * normalised finding.
 *
 * <p>That last one is what makes the whole layer worth having. Every provider
 * returns something different — Roboflow returns {@code predictions} with
 * bounding boxes, a classifier returns {@code label}/{@code score}, a custom
 * endpoint returns whatever its author chose. Downstream, none of that should be
 * visible: the context builder, the confidence policy and the prompt all consume
 * one shape.
 */
public interface SpecialistProvider {

    /** Stable identifier stored on the connection row. */
    String name();

    /** Human label for the console. */
    String label();

    /** Fixed endpoint, or null when the developer must supply one. */
    String defaultBaseUrl();

    SpecialistConnectionEntity.AuthStyle defaultAuthStyle();

    /** Header or query-parameter name this provider expects, when applicable. */
    default String defaultAuthParam() {
        return null;
    }

    /** What kind of input the provider's models consume. */
    default List<String> inputKinds() {
        return List.of("image");
    }

    /**
     * Builds the HTTP call for one invocation.
     *
     * @param modelPath the developer's model identifier, e.g. {@code animal-injury/3}
     * @param input     the request payload, already normalised by the caller
     */
    Call buildCall(SpecialistConnectionEntity connection, String modelPath, Map<String, Object> input);

    /**
     * Maps a provider response onto normalised findings.
     *
     * <p>Implementations must not throw on an unexpected shape. A provider that
     * changed its response format should produce zero findings and let the
     * confidence policy treat that as "no signal", not take the request down.
     */
    List<Finding> parse(Object responseBody);

    /** An outbound call, ready for the hardened HTTP activity. */
    record Call(String url, String method, Map<String, String> headers, Object body) {
    }

    /**
     * One normalised result.
     *
     * @param label      what the specialist believes it saw
     * @param confidence 0–1
     * @param region     optional location within the input, provider-shaped
     */
    record Finding(String label, double confidence, Map<String, Object> region) {
    }
}
