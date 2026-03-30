package io.deadline4j.spring.autoconfigure;

import io.deadline4j.AdaptiveTimeout;
import io.deadline4j.AdaptiveTimeoutRegistry;
import io.deadline4j.DeadlineCodec;
import io.deadline4j.DeadlineTimer;
import io.deadline4j.EnforcementMode;
import io.deadline4j.ServerDeadlineConfig;
import io.deadline4j.ServiceConfig;
import io.deadline4j.ServiceConfigRegistry;
import io.deadline4j.ServicePriority;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Deadline4jAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Deadline4jAutoConfiguration.class));

    @Test
    void coreBeansCreated() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DeadlineCodec.class);
            assertThat(ctx).hasSingleBean(AdaptiveTimeoutRegistry.class);
            assertThat(ctx).hasSingleBean(ServiceConfigRegistry.class);
            assertThat(ctx).hasSingleBean(ServerDeadlineConfig.class);
            assertThat(ctx).hasSingleBean(DeadlineTimer.class);
        });
    }

    @Test
    void customCodecOverridesDefault() {
        contextRunner
                .withBean(DeadlineCodec.class, DeadlineCodec::remainingMillis)
                .run(ctx -> assertThat(ctx).hasSingleBean(DeadlineCodec.class));
    }

    @Test
    void serverDeadlineConfigFromProperty() {
        contextRunner
                .withPropertyValues("deadline4j.server-max-deadline=5s")
                .run(ctx -> {
                    ServerDeadlineConfig config = ctx.getBean(ServerDeadlineConfig.class);
                    assertThat(config.maxDeadline()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void serverDeadlineConfigNoneWhenNotSet() {
        contextRunner.run(ctx -> {
            ServerDeadlineConfig config = ctx.getBean(ServerDeadlineConfig.class);
            assertThat(config.maxDeadline()).isNull();
        });
    }

    @Test
    void serviceConfigResolvesPerService() {
        contextRunner
                .withPropertyValues(
                        "deadline4j.enforcement=observe",
                        "deadline4j.services.my-service.enforcement=enforce",
                        "deadline4j.services.my-service.priority=optional",
                        "deadline4j.services.my-service.min-budget-required=200ms")
                .run(ctx -> {
                    ServiceConfigRegistry registry = ctx.getBean(ServiceConfigRegistry.class);
                    ServiceConfig config = registry.forService("my-service");
                    assertThat(config.enforcement()).isEqualTo(EnforcementMode.ENFORCE);
                    assertThat(config.priority()).isEqualTo(ServicePriority.OPTIONAL);
                    assertThat(config.minBudgetRequired()).isEqualTo(Duration.ofMillis(200));
                });
    }

    @Test
    void unknownServiceFallsBackToGlobal() {
        contextRunner
                .withPropertyValues("deadline4j.enforcement=enforce")
                .run(ctx -> {
                    ServiceConfigRegistry registry = ctx.getBean(ServiceConfigRegistry.class);
                    ServiceConfig config = registry.forService("unknown");
                    assertThat(config.enforcement()).isEqualTo(EnforcementMode.ENFORCE);
                    assertThat(config.priority()).isEqualTo(ServicePriority.REQUIRED);
                });
    }

    @Test
    void adaptiveConfigResolvesPerService() {
        contextRunner
                .withPropertyValues(
                        "deadline4j.adaptive.percentile=0.95",
                        "deadline4j.services.fast-svc.adaptive.percentile=0.999",
                        "deadline4j.services.fast-svc.adaptive.max-timeout=2s")
                .run(ctx -> {
                    AdaptiveTimeoutRegistry registry = ctx.getBean(AdaptiveTimeoutRegistry.class);
                    // fast-svc should use per-service adaptive
                    AdaptiveTimeout timeout = registry.forService("fast-svc");
                    assertThat(timeout).isNotNull();
                    // default service uses global adaptive
                    AdaptiveTimeout defaultTimeout = registry.forService("other");
                    assertThat(defaultTimeout).isNotNull();
                });
    }

    @Test
    void defaultEnforcementIsObserve() {
        contextRunner.run(ctx -> {
            ServiceConfigRegistry registry = ctx.getBean(ServiceConfigRegistry.class);
            ServiceConfig config = registry.forService("any-service");
            assertThat(config.enforcement()).isEqualTo(EnforcementMode.OBSERVE);
        });
    }
}
