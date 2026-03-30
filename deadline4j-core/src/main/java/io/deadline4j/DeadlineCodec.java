package io.deadline4j;

/**
 * SPI interface for serializing and deserializing {@link Deadline}
 * instances to and from a carrier (e.g., HTTP headers, message properties).
 *
 * <p>The default implementation uses {@code X-Deadline-Remaining-Ms} and
 * {@code X-Deadline-Id} headers. Custom implementations can use different
 * wire formats (e.g., absolute timestamps, HLCs).
 *
 * @see CarrierSetter
 * @see CarrierGetter
 */
public interface DeadlineCodec {

    /**
     * Injects a deadline's data onto the given carrier.
     *
     * @param deadline the deadline to serialize
     * @param carrier  the carrier to write to
     * @param setter   the setter for writing key-value pairs
     * @param <C>      the carrier type
     */
    <C> void inject(Deadline deadline, C carrier, CarrierSetter<C> setter);

    /**
     * Extracts a deadline from the given carrier.
     *
     * @param carrier the carrier to read from
     * @param getter  the getter for reading values by key
     * @param <C>     the carrier type
     * @return the extracted deadline, or {@code null} if no valid deadline
     *         data is present
     */
    <C> Deadline extract(C carrier, CarrierGetter<C> getter);

    /**
     * Returns the default codec that uses {@code X-Deadline-Remaining-Ms}
     * and {@code X-Deadline-Id} headers.
     *
     * @return the remaining-millis codec
     */
    static DeadlineCodec remainingMillis() {
        return new RemainingMillisCodec();
    }
}
