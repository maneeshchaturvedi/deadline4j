package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveTimeoutConfigTest {

    // --- Default values ---

    @Test
    void defaults_percentile() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.percentile()).isEqualTo(0.99);
    }

    @Test
    void defaults_minTimeout() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.minTimeout()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void defaults_maxTimeout() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.maxTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void defaults_coldStartTimeout() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.coldStartTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void defaults_minSamples() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.minSamples()).isEqualTo(100);
    }

    @Test
    void defaults_windowSize() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.windowSize()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void defaults_headroomMultiplier() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder().build();
        assertThat(config.headroomMultiplier()).isEqualTo(1.5);
    }

    // --- Builder sets all fields ---

    @Test
    void builder_setsAllFields() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .percentile(0.95)
                .minTimeout(Duration.ofMillis(100))
                .maxTimeout(Duration.ofSeconds(10))
                .coldStartTimeout(Duration.ofSeconds(3))
                .minSamples(50)
                .windowSize(Duration.ofSeconds(120))
                .headroomMultiplier(2.0)
                .build();

        assertThat(config.percentile()).isEqualTo(0.95);
        assertThat(config.minTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.maxTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.coldStartTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.minSamples()).isEqualTo(50);
        assertThat(config.windowSize()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.headroomMultiplier()).isEqualTo(2.0);
    }

    // --- Clamping to ABSOLUTE_MIN/MAX ---

    @Test
    void minTimeout_clampedToAbsoluteMin() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minTimeout(Duration.ZERO)
                .build();
        assertThat(config.minTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void minTimeout_clampedToAbsoluteMax() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minTimeout(Duration.ofMinutes(10))
                .build();
        assertThat(config.minTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void maxTimeout_clampedToAbsoluteMin() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .maxTimeout(Duration.ZERO)
                .build();
        assertThat(config.maxTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void maxTimeout_clampedToAbsoluteMax() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .maxTimeout(Duration.ofHours(1))
                .build();
        assertThat(config.maxTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void coldStartTimeout_clampedToAbsoluteMin() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .coldStartTimeout(Duration.ofNanos(1))
                .build();
        assertThat(config.coldStartTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void coldStartTimeout_clampedToAbsoluteMax() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .coldStartTimeout(Duration.ofMinutes(30))
                .build();
        assertThat(config.coldStartTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    // --- Negative / zero value handling ---

    @Test
    void negativeDuration_clampedToAbsoluteMin() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minTimeout(Duration.ofMillis(-100))
                .maxTimeout(Duration.ofMillis(-500))
                .coldStartTimeout(Duration.ofMillis(-1000))
                .build();
        assertThat(config.minTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(config.maxTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(config.coldStartTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    // --- headroomMultiplier clamping ---

    @Test
    void headroomMultiplier_clampedToMinOne() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .headroomMultiplier(0.5)
                .build();
        assertThat(config.headroomMultiplier()).isEqualTo(1.0);
    }

    @Test
    void headroomMultiplier_negativeClampedToMinOne() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .headroomMultiplier(-2.0)
                .build();
        assertThat(config.headroomMultiplier()).isEqualTo(1.0);
    }

    @Test
    void headroomMultiplier_exactlyOneIsAllowed() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .headroomMultiplier(1.0)
                .build();
        assertThat(config.headroomMultiplier()).isEqualTo(1.0);
    }

    // --- minSamples clamping ---

    @Test
    void minSamples_clampedToAtLeastOne() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minSamples(0)
                .build();
        assertThat(config.minSamples()).isEqualTo(1);
    }

    @Test
    void minSamples_negativeClampedToOne() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minSamples(-10)
                .build();
        assertThat(config.minSamples()).isEqualTo(1);
    }

    @Test
    void minSamples_oneIsAllowed() {
        AdaptiveTimeoutConfig config = AdaptiveTimeoutConfig.builder()
                .minSamples(1)
                .build();
        assertThat(config.minSamples()).isEqualTo(1);
    }
}
