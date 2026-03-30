package io.deadline4j;

import java.time.Duration;

/**
 * Server-side deadline configuration. Immutable.
 *
 * <p>Defines a server-imposed ceiling ({@code maxDeadline}) that inbound
 * filters apply to incoming deadlines. If the incoming deadline exceeds
 * the ceiling, it is capped.
 *
 * <p>Separate from per-outbound-service {@link ServiceConfig}.
 */
public final class ServerDeadlineConfig {

    private static final ServerDeadlineConfig NONE = new ServerDeadlineConfig(null);

    private final Duration maxDeadline; // nullable

    /**
     * Creates a server deadline config with the given ceiling.
     *
     * @param maxDeadline the maximum deadline duration, or null for no ceiling
     */
    public ServerDeadlineConfig(Duration maxDeadline) {
        this.maxDeadline = maxDeadline;
    }

    /**
     * Returns a config with no ceiling (max deadline is null).
     *
     * @return a no-op server deadline config
     */
    public static ServerDeadlineConfig none() {
        return NONE;
    }

    /**
     * Returns the server-imposed maximum deadline, or null if no ceiling.
     *
     * @return the max deadline duration, or null
     */
    public Duration maxDeadline() {
        return maxDeadline;
    }

    /**
     * Applies this config to an incoming deadline.
     *
     * <p>If {@code maxDeadline} is set and is more restrictive than the
     * incoming deadline, returns a new deadline capped to {@code maxDeadline}.
     * Otherwise returns the incoming deadline unchanged.
     *
     * @param incoming the incoming deadline
     * @return the effective deadline after applying the server ceiling
     */
    public Deadline applyTo(Deadline incoming) {
        if (maxDeadline == null) {
            return incoming;
        }
        return incoming.min(Deadline.after(maxDeadline));
    }
}
