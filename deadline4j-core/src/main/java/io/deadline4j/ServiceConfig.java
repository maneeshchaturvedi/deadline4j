package io.deadline4j;

import java.time.Duration;

/**
 * Per-service deadline behavior configuration. Immutable.
 *
 * <p>Controls enforcement mode, service priority, minimum budget
 * required to proceed, and maximum deadline for the service.
 */
public final class ServiceConfig {

    private final EnforcementMode enforcement;
    private final ServicePriority priority;
    private final Duration minBudgetRequired; // nullable
    private final Duration maxDeadline;       // nullable

    private ServiceConfig(Builder b) {
        this.enforcement = b.enforcement;
        this.priority = b.priority;
        this.minBudgetRequired = b.minBudgetRequired;
        this.maxDeadline = b.maxDeadline;
    }

    /** Enforcement mode for this service. */
    public EnforcementMode enforcement() {
        return enforcement;
    }

    /** Priority classification for this service. */
    public ServicePriority priority() {
        return priority;
    }

    /** Minimum remaining budget required to proceed with this call, or null if no minimum. */
    public Duration minBudgetRequired() {
        return minBudgetRequired;
    }

    /** Maximum deadline for this service, or null if no ceiling. */
    public Duration maxDeadline() {
        return maxDeadline;
    }

    /** Create a new builder with default values. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ServiceConfig}.
     */
    public static final class Builder {
        private EnforcementMode enforcement = EnforcementMode.OBSERVE;
        private ServicePriority priority = ServicePriority.REQUIRED;
        private Duration minBudgetRequired = null;
        private Duration maxDeadline = null;

        Builder() {}

        public Builder enforcement(EnforcementMode enforcement) {
            this.enforcement = enforcement;
            return this;
        }

        public Builder priority(ServicePriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder minBudgetRequired(Duration minBudgetRequired) {
            this.minBudgetRequired = minBudgetRequired;
            return this;
        }

        public Builder maxDeadline(Duration maxDeadline) {
            this.maxDeadline = maxDeadline;
            return this;
        }

        public ServiceConfig build() {
            return new ServiceConfig(this);
        }
    }
}
