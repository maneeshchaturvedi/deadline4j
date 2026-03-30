package io.deadline4j;

import java.time.Duration;

/**
 * Configuration for {@link AdaptiveTimeout}. Immutable.
 *
 * <p>All duration fields are clamped to absolute safety bounds:
 * [{@value #ABSOLUTE_MIN_MS}ms, {@value #ABSOLUTE_MAX_MS}ms].
 * These bounds are hardcoded and cannot be overridden by configuration.
 */
public final class AdaptiveTimeoutConfig {

    // Absolute safety bounds. Cannot be overridden by configuration.
    private static final long ABSOLUTE_MIN_MS = 1;
    private static final long ABSOLUTE_MAX_MS = 300_000; // 5 minutes
    private static final Duration ABSOLUTE_MIN = Duration.ofMillis(ABSOLUTE_MIN_MS);
    private static final Duration ABSOLUTE_MAX = Duration.ofMinutes(5);

    private final double percentile;
    private final Duration minTimeout;
    private final Duration maxTimeout;
    private final Duration coldStartTimeout;
    private final int minSamples;
    private final Duration windowSize;
    private final double headroomMultiplier;

    private AdaptiveTimeoutConfig(Builder b) {
        this.percentile = Math.max(0.0, Math.min(1.0, b.percentile));
        this.minTimeout = clamp(b.minTimeout, ABSOLUTE_MIN, ABSOLUTE_MAX);
        this.maxTimeout = clamp(b.maxTimeout, ABSOLUTE_MIN, ABSOLUTE_MAX);
        this.coldStartTimeout = clamp(b.coldStartTimeout, ABSOLUTE_MIN, ABSOLUTE_MAX);
        this.minSamples = Math.max(1, b.minSamples);
        this.windowSize = b.windowSize.isNegative() || b.windowSize.isZero()
                ? Duration.ofSeconds(60) : b.windowSize;
        this.headroomMultiplier = Math.max(1.0, b.headroomMultiplier);
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    /** Percentile of observed latencies to use for timeout computation. */
    public double percentile() { return percentile; }

    /** Minimum timeout floor. */
    public Duration minTimeout() { return minTimeout; }

    /** Maximum timeout ceiling. */
    public Duration maxTimeout() { return maxTimeout; }

    /** Timeout used during cold start (before enough samples collected). */
    public Duration coldStartTimeout() { return coldStartTimeout; }

    /** Minimum number of samples before switching from cold start to adaptive. */
    public int minSamples() { return minSamples; }

    /** Sliding window duration for the histogram. */
    public Duration windowSize() { return windowSize; }

    /** Multiplier applied to the percentile value to provide headroom. */
    public double headroomMultiplier() { return headroomMultiplier; }

    /** Create a new builder with default values. */
    public static Builder builder() { return new Builder(); }

    /**
     * Builder for {@link AdaptiveTimeoutConfig}.
     */
    public static final class Builder {
        private double percentile = 0.99;
        private Duration minTimeout = Duration.ofMillis(50);
        private Duration maxTimeout = Duration.ofSeconds(30);
        private Duration coldStartTimeout = Duration.ofSeconds(5);
        private int minSamples = 100;
        private Duration windowSize = Duration.ofSeconds(60);
        private double headroomMultiplier = 1.5;

        Builder() {}

        public Builder percentile(double v) { this.percentile = v; return this; }
        public Builder minTimeout(Duration v) { this.minTimeout = v; return this; }
        public Builder maxTimeout(Duration v) { this.maxTimeout = v; return this; }
        public Builder coldStartTimeout(Duration v) { this.coldStartTimeout = v; return this; }
        public Builder minSamples(int v) { this.minSamples = v; return this; }
        public Builder windowSize(Duration v) { this.windowSize = v; return this; }
        public Builder headroomMultiplier(double v) { this.headroomMultiplier = v; return this; }
        public AdaptiveTimeoutConfig build() { return new AdaptiveTimeoutConfig(this); }
    }
}
