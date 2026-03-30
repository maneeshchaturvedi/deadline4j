package io.deadline4j;

/**
 * Functional interface for getting a value from a carrier by key.
 *
 * <p>Used by {@link DeadlineCodec#extract} to read deadline data
 * from an inbound carrier (e.g., HTTP headers, message properties).
 *
 * @param <C> the carrier type
 */
@FunctionalInterface
public interface CarrierGetter<C> {

    /**
     * Returns the value associated with the given key, or {@code null}
     * if absent.
     *
     * @param carrier the carrier to read from
     * @param key     the key (e.g., header name)
     * @return the value, or {@code null} if not present
     */
    String get(C carrier, String key);
}
