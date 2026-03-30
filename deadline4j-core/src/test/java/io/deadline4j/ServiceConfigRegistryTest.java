package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceConfigRegistryTest {

    @Test
    void forService_delegatesToConfigSource() {
        ServiceConfig expected = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();

        ServiceConfigRegistry registry = new ServiceConfigRegistry(name -> expected);

        ServiceConfig result = registry.forService("svc-a");
        assertThat(result).isSameAs(expected);
    }

    @Test
    void forService_returnsDifferentConfigsForDifferentServices() {
        ServiceConfig configA = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();
        ServiceConfig configB = ServiceConfig.builder()
                .enforcement(EnforcementMode.OBSERVE)
                .priority(ServicePriority.OPTIONAL)
                .build();

        ServiceConfigRegistry registry = new ServiceConfigRegistry(name -> {
            if ("svc-a".equals(name)) return configA;
            return configB;
        });

        assertThat(registry.forService("svc-a")).isSameAs(configA);
        assertThat(registry.forService("svc-b")).isSameAs(configB);
        assertThat(registry.forService("svc-a").enforcement()).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(registry.forService("svc-b").enforcement()).isEqualTo(EnforcementMode.OBSERVE);
    }
}
