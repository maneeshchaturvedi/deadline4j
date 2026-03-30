package io.deadline4j.it.config;

import io.deadline4j.AdaptiveTimeout;
import io.deadline4j.AdaptiveTimeoutRegistry;
import io.deadline4j.DeadlineCodec;
import io.deadline4j.DeadlineTimer;
import io.deadline4j.EnforcementMode;
import io.deadline4j.ServerDeadlineConfig;
import io.deadline4j.ServiceConfig;
import io.deadline4j.ServiceConfigRegistry;
import io.deadline4j.ServicePriority;
import io.deadline4j.micrometer.Deadline4jMetrics;
import io.deadline4j.spring.autoconfigure.Deadline4jAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that {@link Deadline4jAutoConfiguration}
 * wires beans correctly from properties.
 */
class AutoConfigurationWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Deadline4jAutoConfiguration.class));

    @Test
    void fullAutoConfiguration_allCoreBeans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DeadlineCodec.class);
            assertThat(ctx).hasSingleBean(AdaptiveTimeoutRegistry.class);
            assertThat(ctx).hasSingleBean(ServiceConfigRegistry.class);
            assertThat(ctx).hasSingleBean(ServerDeadlineConfig.class);
            assertThat(ctx).hasSingleBean(DeadlineTimer.class);
        });
    }

    @Test
    void propertiesBindCorrectly() {
        contextRunner
                .withPropertyValues(
                        "deadline4j.enforcement=enforce",
                        "deadline4j.default-deadline=10s",
                        "deadline4j.server-max-deadline=30s",
                        "deadline4j.adaptive.percentile=0.95",
                        "deadline4j.adaptive.max-timeout=5s",
                        "deadline4j.services.inventory-service.priority=required",
                        "deadline4j.services.inventory-service.enforcement=enforce",
                        "deadline4j.services.recommendation-service.priority=optional",
                        "deadline4j.services.recommendation-service.min-budget-required=200ms",
                        "deadline4j.services.recommendation-service.enforcement=enforce"
                )
                .run(ctx -> {
                    // Verify ServerDeadlineConfig bound
                    ServerDeadlineConfig serverConfig = ctx.getBean(ServerDeadlineConfig.class);
                    assertThat(serverConfig.maxDeadline()).isEqualTo(Duration.ofSeconds(30));

                    // Verify per-service config
                    ServiceConfigRegistry registry = ctx.getBean(ServiceConfigRegistry.class);
                    ServiceConfig invConfig = registry.forService("inventory-service");
                    assertThat(invConfig.enforcement()).isEqualTo(EnforcementMode.ENFORCE);
                    assertThat(invConfig.priority()).isEqualTo(ServicePriority.REQUIRED);

                    ServiceConfig recConfig = registry.forService("recommendation-service");
                    assertThat(recConfig.priority()).isEqualTo(ServicePriority.OPTIONAL);
                    assertThat(recConfig.minBudgetRequired()).isEqualTo(Duration.ofMillis(200));

                    // Unknown service falls back to global enforcement
                    ServiceConfig unknown = registry.forService("unknown-service");
                    assertThat(unknown.enforcement()).isEqualTo(EnforcementMode.ENFORCE);
                    assertThat(unknown.priority()).isEqualTo(ServicePriority.REQUIRED);
                });
    }

    @Test
    void perServiceAdaptiveConfig() {
        contextRunner
                .withPropertyValues(
                        "deadline4j.adaptive.percentile=0.99",
                        "deadline4j.adaptive.max-timeout=30s",
                        "deadline4j.services.fast-svc.adaptive.percentile=0.95",
                        "deadline4j.services.fast-svc.adaptive.max-timeout=2s"
                )
                .run(ctx -> {
                    AdaptiveTimeoutRegistry registry = ctx.getBean(AdaptiveTimeoutRegistry.class);
                    // Exercise both services to verify config resolution
                    AdaptiveTimeout fast = registry.forService("fast-svc");
                    AdaptiveTimeout normal = registry.forService("normal-svc");
                    assertThat(fast).isNotNull();
                    assertThat(normal).isNotNull();
                    // Both should be distinct instances with different configs
                    assertThat(fast).isNotSameAs(normal);
                });
    }

    @Test
    void customBeanOverridesDefault() {
        contextRunner
                .withBean("customCodec", DeadlineCodec.class, DeadlineCodec::remainingMillis)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DeadlineCodec.class);
                    // The custom bean should be the one in the context
                    assertThat(ctx.getBean(DeadlineCodec.class)).isNotNull();
                });
    }

    @Test
    void micrometerMetricsIntegration() {
        contextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    Deadline4jMetrics metrics = new Deadline4jMetrics(registry);

                    metrics.recordCallDuration("test-svc", "success", Duration.ofMillis(100));
                    metrics.recordDeadlineExceeded("test-svc", "before_call", "enforce");
                    metrics.recordCallSkipped("optional-svc");

                    assertThat(registry.find("deadline4j.call.duration")
                            .tag("service", "test-svc").timer()).isNotNull();
                    assertThat(registry.find("deadline4j.deadline.exceeded")
                            .tag("service", "test-svc").counter()).isNotNull();
                    assertThat(registry.find("deadline4j.deadline.exceeded")
                            .tag("service", "test-svc").counter().count()).isEqualTo(1.0);
                    assertThat(registry.find("deadline4j.call.skipped")
                            .tag("service", "optional-svc").counter()).isNotNull();
                    assertThat(registry.find("deadline4j.call.skipped")
                            .tag("service", "optional-svc").counter().count()).isEqualTo(1.0);
                });
    }

    @Test
    void serverDeadlineConfigNone_whenPropertyNotSet() {
        contextRunner.run(ctx -> {
            ServerDeadlineConfig config = ctx.getBean(ServerDeadlineConfig.class);
            assertThat(config.maxDeadline()).isNull();
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
