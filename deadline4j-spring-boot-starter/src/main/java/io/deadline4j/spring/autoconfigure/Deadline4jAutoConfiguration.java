package io.deadline4j.spring.autoconfigure;

import io.deadline4j.AdaptiveTimeoutRegistry;
import io.deadline4j.DeadlineCodec;
import io.deadline4j.DeadlineTimer;
import io.deadline4j.ServerDeadlineConfig;
import io.deadline4j.ServiceConfig;
import io.deadline4j.ServiceConfigRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for deadline4j core beans.
 *
 * <p>Registers shared infrastructure beans ({@link DeadlineCodec},
 * {@link AdaptiveTimeoutRegistry}, {@link ServiceConfigRegistry},
 * {@link ServerDeadlineConfig}, {@link DeadlineTimer}) that are
 * consumed by the web-framework-specific configurations.
 *
 * <p>All beans are conditional on missing bean, so applications can
 * provide their own implementations.
 */
@AutoConfiguration
@EnableConfigurationProperties(Deadline4jProperties.class)
public class Deadline4jAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeadlineCodec deadlineCodec() {
        return DeadlineCodec.remainingMillis();
    }

    @Bean
    @ConditionalOnMissingBean
    public AdaptiveTimeoutRegistry adaptiveTimeoutRegistry(Deadline4jProperties props) {
        return new AdaptiveTimeoutRegistry(serviceName -> {
            Deadline4jProperties.ServiceProperties svc = props.getServices().get(serviceName);
            Deadline4jProperties.AdaptiveDefaults adaptive =
                    (svc != null && svc.getAdaptive() != null)
                            ? svc.getAdaptive()
                            : props.getAdaptive();
            return adaptive.toConfig();
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public ServiceConfigRegistry serviceConfigRegistry(Deadline4jProperties props) {
        return new ServiceConfigRegistry(serviceName -> {
            Deadline4jProperties.ServiceProperties svc = props.getServices().get(serviceName);
            if (svc == null) {
                return ServiceConfig.builder()
                        .enforcement(props.getEnforcement())
                        .build();
            }
            return ServiceConfig.builder()
                    .enforcement(svc.getEnforcement() != null
                            ? svc.getEnforcement()
                            : props.getEnforcement())
                    .priority(svc.getPriority())
                    .minBudgetRequired(svc.getMinBudgetRequired())
                    .maxDeadline(svc.getMaxDeadline())
                    .build();
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public ServerDeadlineConfig serverDeadlineConfig(Deadline4jProperties props) {
        return props.getServerMaxDeadline() != null
                ? new ServerDeadlineConfig(props.getServerMaxDeadline())
                : ServerDeadlineConfig.none();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadlineTimer deadlineTimer() {
        return DeadlineTimer.defaultTimer();
    }
}
