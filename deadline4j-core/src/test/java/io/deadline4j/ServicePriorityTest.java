package io.deadline4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServicePriorityTest {

    @Test
    void values_containsAllConstants() {
        ServicePriority[] values = ServicePriority.values();

        assertThat(values).containsExactly(
                ServicePriority.REQUIRED,
                ServicePriority.OPTIONAL
        );
    }

    @Test
    void valueOf_resolvesAllConstants() {
        assertThat(ServicePriority.valueOf("REQUIRED")).isEqualTo(ServicePriority.REQUIRED);
        assertThat(ServicePriority.valueOf("OPTIONAL")).isEqualTo(ServicePriority.OPTIONAL);
    }
}
