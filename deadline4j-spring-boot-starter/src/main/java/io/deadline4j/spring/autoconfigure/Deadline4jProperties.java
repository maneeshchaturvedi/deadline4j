package io.deadline4j.spring.autoconfigure;

import io.deadline4j.AdaptiveTimeoutConfig;
import io.deadline4j.EnforcementMode;
import io.deadline4j.ServicePriority;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot configuration properties for deadline4j, bound to the
 * {@code deadline4j.*} namespace.
 */
@ConfigurationProperties(prefix = "deadline4j")
public class Deadline4jProperties {

    /** Master enforcement switch. Default: OBSERVE (safe). */
    private EnforcementMode enforcement = EnforcementMode.OBSERVE;

    /** Default deadline for requests arriving without X-Deadline-Remaining-Ms. */
    private Duration defaultDeadline;

    /** Server-imposed maximum deadline ceiling. */
    private Duration serverMaxDeadline;

    /** Global adaptive timeout defaults. */
    private AdaptiveDefaults adaptive = new AdaptiveDefaults();

    /** Per-service overrides keyed by service name. */
    private Map<String, ServiceProperties> services = new LinkedHashMap<>();

    public EnforcementMode getEnforcement() {
        return enforcement;
    }

    public void setEnforcement(EnforcementMode enforcement) {
        this.enforcement = enforcement;
    }

    public Duration getDefaultDeadline() {
        return defaultDeadline;
    }

    public void setDefaultDeadline(Duration defaultDeadline) {
        this.defaultDeadline = defaultDeadline;
    }

    public Duration getServerMaxDeadline() {
        return serverMaxDeadline;
    }

    public void setServerMaxDeadline(Duration serverMaxDeadline) {
        this.serverMaxDeadline = serverMaxDeadline;
    }

    public AdaptiveDefaults getAdaptive() {
        return adaptive;
    }

    public void setAdaptive(AdaptiveDefaults adaptive) {
        this.adaptive = adaptive;
    }

    public Map<String, ServiceProperties> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceProperties> services) {
        this.services = services;
    }

    /**
     * Adaptive timeout defaults that map to {@link AdaptiveTimeoutConfig}.
     */
    public static class AdaptiveDefaults {

        private double percentile = 0.99;
        private Duration minTimeout = Duration.ofMillis(50);
        private Duration maxTimeout = Duration.ofSeconds(30);
        private Duration coldStartTimeout = Duration.ofSeconds(5);
        private int minSamples = 100;
        private Duration windowSize = Duration.ofSeconds(60);
        private double headroomMultiplier = 1.5;

        public double getPercentile() {
            return percentile;
        }

        public void setPercentile(double percentile) {
            this.percentile = percentile;
        }

        public Duration getMinTimeout() {
            return minTimeout;
        }

        public void setMinTimeout(Duration minTimeout) {
            this.minTimeout = minTimeout;
        }

        public Duration getMaxTimeout() {
            return maxTimeout;
        }

        public void setMaxTimeout(Duration maxTimeout) {
            this.maxTimeout = maxTimeout;
        }

        public Duration getColdStartTimeout() {
            return coldStartTimeout;
        }

        public void setColdStartTimeout(Duration coldStartTimeout) {
            this.coldStartTimeout = coldStartTimeout;
        }

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int minSamples) {
            this.minSamples = minSamples;
        }

        public Duration getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(Duration windowSize) {
            this.windowSize = windowSize;
        }

        public double getHeadroomMultiplier() {
            return headroomMultiplier;
        }

        public void setHeadroomMultiplier(double headroomMultiplier) {
            this.headroomMultiplier = headroomMultiplier;
        }

        /** Convert to core {@link AdaptiveTimeoutConfig}. */
        public AdaptiveTimeoutConfig toConfig() {
            return AdaptiveTimeoutConfig.builder()
                    .percentile(percentile)
                    .minTimeout(minTimeout)
                    .maxTimeout(maxTimeout)
                    .coldStartTimeout(coldStartTimeout)
                    .minSamples(minSamples)
                    .windowSize(windowSize)
                    .headroomMultiplier(headroomMultiplier)
                    .build();
        }
    }

    /**
     * Per-service configuration overrides.
     */
    public static class ServiceProperties {

        /** Per-service enforcement. Null = inherit global. */
        private EnforcementMode enforcement;

        private ServicePriority priority = ServicePriority.REQUIRED;

        private Duration minBudgetRequired;

        private Duration maxDeadline;

        /** Per-service adaptive overrides. Null = inherit global. */
        private AdaptiveDefaults adaptive;

        public EnforcementMode getEnforcement() {
            return enforcement;
        }

        public void setEnforcement(EnforcementMode enforcement) {
            this.enforcement = enforcement;
        }

        public ServicePriority getPriority() {
            return priority;
        }

        public void setPriority(ServicePriority priority) {
            this.priority = priority;
        }

        public Duration getMinBudgetRequired() {
            return minBudgetRequired;
        }

        public void setMinBudgetRequired(Duration minBudgetRequired) {
            this.minBudgetRequired = minBudgetRequired;
        }

        public Duration getMaxDeadline() {
            return maxDeadline;
        }

        public void setMaxDeadline(Duration maxDeadline) {
            this.maxDeadline = maxDeadline;
        }

        public AdaptiveDefaults getAdaptive() {
            return adaptive;
        }

        public void setAdaptive(AdaptiveDefaults adaptive) {
            this.adaptive = adaptive;
        }
    }
}
