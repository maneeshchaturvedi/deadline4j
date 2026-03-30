package io.deadline4j;

/**
 * Functional interface for setting a key-value pair on a carrier.
 *
 * <p>Used by {@link DeadlineCodec#inject} to write deadline data
 * onto an outbound carrier (e.g., HTTP headers, message properties).
 *
 * @param <C> the carrier type
 */
@FunctionalInterface
public interface CarrierSetter<C> {

    /**
     * Sets a key-value pair on the given carrier.
     *
     * @param carrier the carrier to write to
     * @param key     the key (e.g., header name)
     * @param value   the value (e.g., header value)
     */
    void set(C carrier, String key, String value);
}
