package io.deadline4j;

import java.time.Duration;

/**
 * Computes dynamic timeouts based on observed latency distributions.
 *
 * <p>Backed by a sliding-window HdrHistogram. The timeout is set at a
 * configurable percentile of observed latencies, multiplied by a
 * headroom factor, and clamped to absolute bounds.
 *
 * <p>Thread-safe. One instance per downstream service, shared across
 * all request threads.
 */
public final class AdaptiveTimeout {

    private final String name;
    private final AdaptiveTimeoutConfig config;
    private final SlidingWindowHistogram histogram;

    /**
     * Creates a new adaptive timeout tracker.
     * Package-private: instances are created by {@code AdaptiveTimeoutRegistry}.
     */
    AdaptiveTimeout(String name, AdaptiveTimeoutConfig config) {
        this.name = name;
        this.config = config;
        this.histogram = new SlidingWindowHistogram(
                config.windowSize().toNanos(),
                config.maxTimeout().toMillis() * 2  // track beyond max for distribution visibility
        );
    }

    /** Record an observed latency. Called by interceptors, not application code. */
    public void recordLatency(Duration latency) {
        histogram.recordValue(latency.toMillis());
    }

    /**
     * Current adaptive timeout value.
     *
     * <p>During cold start (fewer than {@code minSamples} observations),
     * returns {@code coldStartTimeout}.
     *
     * <p>After warm-up: percentile x headroomMultiplier, clamped to
     * [minTimeout, maxTimeout].
     */
    public Duration currentTimeout() {
        if (histogram.totalCount() < config.minSamples()) {
            return config.coldStartTimeout();
        }
        long percentileMs = histogram.getValueAtPercentile(
                config.percentile() * 100.0);
        long withHeadroom = (long) (percentileMs * config.headroomMultiplier());
        long clamped = Math.max(config.minTimeout().toMillis(),
                Math.min(withHeadroom, config.maxTimeout().toMillis()));
        return Duration.ofMillis(clamped);
    }

    /**
     * Effective timeout: min(adaptive timeout, remaining deadline).
     * This is what interceptors use. Mirrors gRPC's
     * effectiveDeadline = min(callOptions.deadline, context.deadline).
     */
    public Duration effectiveTimeout(Deadline deadline) {
        Duration adaptive = currentTimeout();
        Duration remaining = deadline.remaining();
        return remaining.compareTo(adaptive) < 0 ? remaining : adaptive;
    }

    /** Raw percentile value in millis (before headroom). For observability. */
    public long currentPercentileMillis() {
        return histogram.getValueAtPercentile(config.percentile() * 100.0);
    }

    /** Number of samples recorded. */
    public long sampleCount() {
        return histogram.totalCount();
    }

    /** Name of this adaptive timeout (typically the downstream service name). */
    public String name() {
        return name;
    }
}
