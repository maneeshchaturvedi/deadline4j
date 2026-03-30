package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveTimeoutTest {

    private static final AdaptiveTimeoutConfig DEFAULT_CONFIG =
            AdaptiveTimeoutConfig.builder().build();

    // --- Cold start ---

    @Test
    void coldStart_returnsColdStartTimeout() {
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", DEFAULT_CONFIG);
        // No samples recorded — should return coldStartTimeout (5s default)
        assertThat(at.currentTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void coldStart_withSomeSamplesButBelowMinSamples() {
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", DEFAULT_CONFIG);
        // Record 99 samples (default minSamples = 100)
        for (int i = 0; i < 99; i++) {
            at.recordLatency(Duration.ofMillis(100));
        }
        assertThat(at.currentTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    // --- Warm-up transition ---

    @Test
    void warmUp_transitionsAtMinSamples() {
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", DEFAULT_CONFIG);
        // Record exactly minSamples (100)
        for (int i = 0; i < 100; i++) {
            at.recordLatency(Duration.ofMillis(100));
        }
        // Should no longer return cold start timeout
        Duration timeout = at.currentTimeout();
        assertThat(timeout).isNotEqualTo(Duration.ofSeconds(5));
        // With all values at 100ms, P99 ~ 100ms, * 1.5 headroom = 150ms
        assertThat(timeout.toMillis()).isBetween(100L, 200L);
    }

    // --- Percentile computation with headroom ---

    @Test
    void percentileWithHeadroom_computedCorrectly() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .percentile(0.99)
                .headroomMultiplier(2.0)
                .minSamples(10)
                .minTimeout(Duration.ofMillis(1))
                .maxTimeout(Duration.ofSeconds(30))
                .build();
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", config);

        for (int i = 0; i < 100; i++) {
            at.recordLatency(Duration.ofMillis(200));
        }

        Duration timeout = at.currentTimeout();
        // P99 of uniform 200ms = 200ms, * 2.0 headroom = 400ms
        assertThat(timeout.toMillis()).isBetween(350L, 450L);
    }

    // --- Clamping to [minTimeout, maxTimeout] ---

    @Test
    void clamping_toMinTimeout() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minTimeout(Duration.ofMillis(500))
                .maxTimeout(Duration.ofSeconds(30))
                .headroomMultiplier(1.0)
                .minSamples(10)
                .build();
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", config);

        // Record very low latencies
        for (int i = 0; i < 20; i++) {
            at.recordLatency(Duration.ofMillis(1));
        }

        Duration timeout = at.currentTimeout();
        // P99 * 1.0 = ~1ms, but minTimeout is 500ms
        assertThat(timeout).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void clamping_toMaxTimeout() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minTimeout(Duration.ofMillis(50))
                .maxTimeout(Duration.ofSeconds(1))
                .headroomMultiplier(2.0)
                .minSamples(10)
                .build();
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", config);

        // Record high latencies
        for (int i = 0; i < 20; i++) {
            at.recordLatency(Duration.ofSeconds(5));
        }

        Duration timeout = at.currentTimeout();
        // P99 * 2.0 would be 10s, but maxTimeout is 1s
        assertThat(timeout).isEqualTo(Duration.ofSeconds(1));
    }

    // --- effectiveTimeout ---

    @Test
    void effectiveTimeout_returnsMinOfAdaptiveAndDeadline() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .coldStartTimeout(Duration.ofSeconds(5))
                .build();
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", config);
        // Cold start => 5s

        // Deadline with 2s remaining — should win
        Deadline deadline = Deadline.after(Duration.ofSeconds(2));
        Duration effective = at.effectiveTimeout(deadline);
        assertThat(effective.toMillis()).isLessThanOrEqualTo(2000);
    }

    @Test
    void effectiveTimeout_adaptiveWinsWhenShorterThanDeadline() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .coldStartTimeout(Duration.ofMillis(500))
                .build();
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", config);
        // Cold start => 500ms

        // Deadline with 10s remaining — adaptive wins
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));
        Duration effective = at.effectiveTimeout(deadline);
        assertThat(effective).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void effectiveTimeout_expiredDeadline_returnsZero() {
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", DEFAULT_CONFIG);

        // Create an already-expired deadline
        Deadline deadline = Deadline.after(Duration.ZERO);
        Duration effective = at.effectiveTimeout(deadline);
        assertThat(effective).isEqualTo(Duration.ZERO);
    }

    // --- name getter ---

    @Test
    void name_returnsConfiguredName() {
        AdaptiveTimeout at = new AdaptiveTimeout("my-service", DEFAULT_CONFIG);
        assertThat(at.name()).isEqualTo("my-service");
    }

    // --- sampleCount ---

    @Test
    void sampleCount_reflectsRecordedLatencies() {
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", DEFAULT_CONFIG);
        assertThat(at.sampleCount()).isEqualTo(0);

        at.recordLatency(Duration.ofMillis(100));
        at.recordLatency(Duration.ofMillis(200));
        at.recordLatency(Duration.ofMillis(300));
        assertThat(at.sampleCount()).isEqualTo(3);
    }

    // --- currentPercentileMillis ---

    @Test
    void currentPercentileMillis_returnsRawValue() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minSamples(5)
                .build();
        AdaptiveTimeout at = new AdaptiveTimeout("test-service", config);

        for (int i = 0; i < 10; i++) {
            at.recordLatency(Duration.ofMillis(250));
        }

        long rawMs = at.currentPercentileMillis();
        assertThat(rawMs).isBetween(249L, 251L);
    }
}
