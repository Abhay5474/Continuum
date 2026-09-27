package io.continuum.provider;

/**
 * Receives an answer as the provider produces it, a piece at a time.
 *
 * <p>Throwing stops the stream: a caller that has gone away should not keep a
 * provider generating tokens nobody will read.
 */
@FunctionalInterface
public interface TokenSink {
    void accept(String delta) throws Exception;
}
