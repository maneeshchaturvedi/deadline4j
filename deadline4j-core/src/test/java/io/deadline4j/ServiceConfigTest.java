package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceConfigTest {

    @Test
    void builder_defaults() {
        ServiceConfig config = ServiceConfig.builder().build();

        assertThat(config.enforcement()).isEqualTo(EnforcementMode.OBSERVE);
        assertThat(config.priority()).isEqualTo(ServicePriority.REQUIRED);
        assertThat(config.minBudgetRequired()).isNull();
        assertThat(config.maxDeadline()).isNull();
    }

    @Test
    void builder_setsEnforcement() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();

        assertThat(config.enforcement()).isEqualTo(EnforcementMode.ENFORCE);
    }

    @Test
    void builder_setsPriority() {
        ServiceConfig config = ServiceConfig.builder()
                .priority(ServicePriority.OPTIONAL)
                .build();

        assertThat(config.priority()).isEqualTo(ServicePriority.OPTIONAL);
    }

    @Test
    void builder_setsMinBudgetRequired() {
        Duration minBudget = Duration.ofMillis(200);
        ServiceConfig config = ServiceConfig.builder()
                .minBudgetRequired(minBudget)
                .build();

        assertThat(config.minBudgetRequired()).isEqualTo(minBudget);
    }

    @Test
    void builder_setsMaxDeadline() {
        Duration maxDeadline = Duration.ofSeconds(10);
        ServiceConfig config = ServiceConfig.builder()
                .maxDeadline(maxDeadline)
                .build();

        assertThat(config.maxDeadline()).isEqualTo(maxDeadline);
    }

    @Test
    void builder_setsAllFields() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .priority(ServicePriority.OPTIONAL)
                .minBudgetRequired(Duration.ofMillis(100))
                .maxDeadline(Duration.ofSeconds(5))
                .build();

        assertThat(config.enforcement()).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(config.priority()).isEqualTo(ServicePriority.OPTIONAL);
        assertThat(config.minBudgetRequired()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.maxDeadline()).isEqualTo(Duration.ofSeconds(5));
    }
}
