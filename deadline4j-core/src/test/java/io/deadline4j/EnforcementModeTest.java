package io.deadline4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnforcementModeTest {

    @Test
    void values_containsAllConstants() {
        EnforcementMode[] values = EnforcementMode.values();

        assertThat(values).containsExactly(
                EnforcementMode.OBSERVE,
                EnforcementMode.ENFORCE,
                EnforcementMode.DISABLED
        );
    }

    @Test
    void valueOf_resolvesAllConstants() {
        assertThat(EnforcementMode.valueOf("OBSERVE")).isEqualTo(EnforcementMode.OBSERVE);
        assertThat(EnforcementMode.valueOf("ENFORCE")).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(EnforcementMode.valueOf("DISABLED")).isEqualTo(EnforcementMode.DISABLED);
    }
}
