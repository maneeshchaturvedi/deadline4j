package io.deadline4j.it.core;

import io.deadline4j.AdaptiveTimeoutConfig;
import io.deadline4j.AdaptiveTimeoutRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the cold-start to adaptive transition in AdaptiveTimeout.
 */
class ColdStartTransitionTest {

    @Test
    void coldStartTimeoutUsedUntilMinSamplesReached() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minSamples(10)
                .coldStartTimeout(Duration.ofMillis(5000))
                .percentile(0.99)
                .headroomMultiplier(1.5)
                .minTimeout(Duration.ofMillis(10))
                .maxTimeout(Duration.ofSeconds(30))
                .windowSize(Duration.ofSeconds(60))
                .build();

        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> config);
        var adaptive = registry.forService("test-service");

        // Record 9 latencies of 100ms each -- still in cold start
        for (int i = 0; i < 9; i++) {
            adaptive.recordLatency(Duration.ofMillis(100));
        }

        assertThat(adaptive.currentTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(adaptive.sampleCount()).isEqualTo(9);

        // Record 10th latency -- should switch to adaptive
        adaptive.recordLatency(Duration.ofMillis(100));
        assertThat(adaptive.sampleCount()).isEqualTo(10);

        Duration adaptiveTimeout = adaptive.currentTimeout();
        assertThat(adaptiveTimeout).isNotEqualTo(Duration.ofMillis(5000));
        // With 100ms latencies at p99 and 1.5x headroom: ~150ms
        assertThat(adaptiveTimeout.toMillis()).isBetween(100L, 200L);
    }

    @Test
    void adaptiveTimeoutRespectsMinAndMaxBounds() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minSamples(5)
                .coldStartTimeout(Duration.ofMillis(5000))
                .percentile(0.99)
                .headroomMultiplier(1.5)
                .minTimeout(Duration.ofMillis(200))
                .maxTimeout(Duration.ofMillis(500))
                .windowSize(Duration.ofSeconds(60))
                .build();

        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> config);
        var adaptive = registry.forService("bounded-service");

        // Record 10 latencies of 10ms -- p99 * 1.5 = 15ms, but min is 200ms
        for (int i = 0; i < 10; i++) {
            adaptive.recordLatency(Duration.ofMillis(10));
        }
        assertThat(adaptive.currentTimeout().toMillis()).isGreaterThanOrEqualTo(200L);

        // Record 10 very high latencies of 1000ms -- p99 * 1.5 = 1500ms, but max is 500ms
        for (int i = 0; i < 10; i++) {
            adaptive.recordLatency(Duration.ofMillis(1000));
        }
        assertThat(adaptive.currentTimeout().toMillis()).isLessThanOrEqualTo(500L);
    }
}
