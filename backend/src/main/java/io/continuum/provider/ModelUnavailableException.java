package io.continuum.provider;

/**
 * The provider answered, and the answer was about the model rather than the
 * request: it is gone (retired or renamed), or this key has no free quota for it.
 *
 * <p>The message is the provider's error, unchanged, so anything that reads it
 * keeps working. The router uses the type to tell the catalogue, which stops
 * sending traffic to the model and checks the provider's list to confirm.
 */
public class ModelUnavailableException extends RuntimeException {

    public enum Reason { GONE, NOT_FREE }

    private final String provider;
    private final String model;
    private final Reason reason;

    public ModelUnavailableException(String provider, String model, Reason reason, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.provider = provider;
        this.model = model;
        this.reason = reason;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public Reason reason() {
        return reason;
    }
}
