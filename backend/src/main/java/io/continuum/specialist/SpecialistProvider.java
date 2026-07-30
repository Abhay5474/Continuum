package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;

import java.util.ArrayList;
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

    /**
     * Maps a provider response onto general {@link Evidence}.
     *
     * <p>The generalised form of {@link #parse}. An adapter that only ever
     * returns detections need not implement it — the default lifts its findings
     * into detection evidence, so {@link RoboflowProvider} and every custom
     * adapter written before this method existed keep working untouched.
     *
     * <p>An adapter <b>must</b> override it when its provider can return
     * something that is not a labelled score: recovered text, named fields,
     * tabular rows. Those cannot be expressed as a {@code Finding} without
     * inventing a confidence, and inventing one is the failure this exists to
     * prevent.
     */
    default List<Evidence> parseEvidence(Object responseBody) {
        List<Evidence> out = new ArrayList<>();
        for (Finding f : parse(responseBody)) {
            out.add(Evidence.detection(f.label(), f.confidence(), f.region()));
        }
        return out;
    }

    /**
     * A further call this provider needs before it has an answer.
     *
     * <p>Most providers answer in one round trip. Some cannot: AssemblyAI takes
     * an upload, then a job submission, then polling until the job finishes.
     * That is a quirk of one provider, so it belongs inside that provider's
     * adapter rather than in the invoker — which is what this seam is for.
     *
     * @param call  what to send next
     * @param delayMillis how long to wait first, for a provider being polled
     */
    record Next(Call call, int delayMillis) {

        public static Next of(Call call) {
            return new Next(call, 0);
        }

        public static Next after(int delayMillis, Call call) {
            return new Next(call, delayMillis);
        }
    }

    /**
     * What to send after a response arrives, or null when this response is the
     * answer.
     *
     * <p>Called with the parsed body of the round that just returned, so an
     * adapter can carry state through the provider's own identifiers — an upload
     * URL, a job id — rather than the invoker holding a session.
     *
     * <p>Must not throw. An adapter that cannot work out what to do next should
     * return null and let {@link #parseEvidence} report what it has.
     *
     * @param round 1 for the response to the first call
     */
    default Next next(SpecialistConnectionEntity connection, Object responseBody, int round) {
        return null;
    }

    /**
     * A ceiling on round trips, so a provider that never reports completion
     * cannot hold a customer request open indefinitely.
     */
    default int maxRounds() {
        return 1;
    }

    /**
     * An outbound call, ready for the hardened HTTP activity.
     *
     * <p>{@code body} may be a {@code String} (sent verbatim), a {@code byte[]}
     * (sent raw, for providers that take the media file as the request body), or
     * any other object, which is serialised as JSON.
     */
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

    /**
     * Forces every adapter's confidences onto the 0–1 scale the rest of the
     * system assumes.
     *
     * <p>Applied centrally rather than trusted to each adapter. Nothing else in
     * Continuum re-checks the range: the reporting threshold, the context
     * builder's bands and the confidence policy all compare against numbers
     * between 0 and 1, and a single value outside it sails past all three. An
     * endpoint scoring out of 100 would be read as maximally confident by every
     * one of them.
     *
     * <p>Found by driving a pipeline whose detector returned an empty result and
     * an envelope field of {@code 128}, which arrived at the policy as
     * "12800% confident" and selected STRONG.
     *
     * <p>The rules:
     * <ul>
     *   <li>0–1 is taken as given.</li>
     *   <li>Above 1 and up to 100 is treated as a percentage and divided. A
     *       score that was never a percentage — a logit, a vote count — becomes
     *       a small number and is filtered out. That is the safe direction to be
     *       wrong in; the other one produces confident advice from noise.</li>
     *   <li>Above 100, or negative, or not a number, is not a confidence on any
     *       scale worth guessing at, and the finding is dropped.</li>
     * </ul>
     */
    static List<Finding> normalise(List<Finding> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Finding> out = new java.util.ArrayList<>(raw.size());
        for (Finding f : raw) {
            double c = f.confidence();
            if (Double.isNaN(c) || Double.isInfinite(c) || c < 0 || c > 100) {
                continue;
            }
            out.add(c <= 1 ? f : new Finding(f.label(), c / 100.0, f.region()));
        }
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }
}
