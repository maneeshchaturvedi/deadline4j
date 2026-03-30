package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServerDeadlineConfigTest {

    @Test
    void none_returnsConfigWithNullMaxDeadline() {
        ServerDeadlineConfig config = ServerDeadlineConfig.none();

        assertThat(config.maxDeadline()).isNull();
    }

    @Test
    void maxDeadline_returnsConfiguredValue() {
        Duration ceiling = Duration.ofSeconds(10);
        ServerDeadlineConfig config = new ServerDeadlineConfig(ceiling);

        assertThat(config.maxDeadline()).isEqualTo(ceiling);
    }

    @Test
    void applyTo_withNoCeiling_returnsInputDeadlineUnchanged() {
        ServerDeadlineConfig config = ServerDeadlineConfig.none();
        Deadline incoming = Deadline.after(Duration.ofSeconds(30));

        Deadline result = config.applyTo(incoming);

        assertThat(result).isSameAs(incoming);
    }

    @Test
    void applyTo_withMoreRestrictiveCeiling_capsTheDeadline() {
        // Server ceiling: 2 seconds
        ServerDeadlineConfig config = new ServerDeadlineConfig(Duration.ofSeconds(2));
        // Incoming deadline: 30 seconds from now
        Deadline incoming = Deadline.after(Duration.ofSeconds(30));

        Deadline result = config.applyTo(incoming);

        // Result should be capped to ~2 seconds
        assertThat(result.remainingMillis()).isLessThanOrEqualTo(2100);
        assertThat(result.remainingMillis()).isGreaterThan(1500);
        // Should not be the same object as incoming
        assertThat(result).isNotSameAs(incoming);
    }

    @Test
    void applyTo_incomingDeadlineShorterThanCeiling_returnsIncoming() {
        // Server ceiling: 30 seconds
        ServerDeadlineConfig config = new ServerDeadlineConfig(Duration.ofSeconds(30));
        // Incoming deadline: 2 seconds from now (more restrictive)
        Deadline incoming = Deadline.after(Duration.ofSeconds(2));

        Deadline result = config.applyTo(incoming);

        // Incoming is more restrictive, so it should win
        assertThat(result.remainingMillis()).isLessThanOrEqualTo(2100);
        assertThat(result.remainingMillis()).isGreaterThan(1500);
    }
}
