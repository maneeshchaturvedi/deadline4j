package io.deadline4j.micrometer;

import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Records deadline4j metrics via Micrometer.
 * All metrics are created lazily on first use.
 */
public class Deadline4jMetrics {

    private final MeterRegistry registry;

    public Deadline4jMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record duration of a downstream call. */
    public void recordCallDuration(String service, String outcome, Duration duration) {
        Timer.builder("deadline4j.call.duration")
            .tag("service", service)
            .tag("outcome", outcome)
            .register(registry)
            .record(duration);
    }

    /** Register a gauge for the current adaptive timeout of a service. */
    public void registerAdaptiveTimeoutGauge(String service, Supplier<Number> valueSupplier) {
        Gauge.builder("deadline4j.adaptive.timeout.ms", valueSupplier)
            .tag("service", service)
            .register(registry);
    }

    /** Register a gauge for the raw percentile value (before headroom). */
    public void registerAdaptivePercentileGauge(String service, Supplier<Number> valueSupplier) {
        Gauge.builder("deadline4j.adaptive.percentile.ms", valueSupplier)
            .tag("service", service)
            .register(registry);
    }

    /** Record fraction of budget consumed when request completes. */
    public void recordBudgetConsumedRatio(double ratio) {
        DistributionSummary.builder("deadline4j.budget.consumed.ratio")
            .register(registry)
            .record(ratio);
    }

    /** Record a deadline exceeded event. */
    public void recordDeadlineExceeded(String service, String phase, String mode) {
        Counter.builder("deadline4j.deadline.exceeded")
            .tag("service", service)
            .tag("phase", phase)
            .tag("mode", mode)
            .register(registry)
            .increment();
    }

    /** Record an optional call being skipped. */
    public void recordCallSkipped(String service) {
        Counter.builder("deadline4j.call.skipped")
            .tag("service", service)
            .register(registry)
            .increment();
    }

    /** Record remaining budget when a downstream call starts. */
    public void recordRemainingAtCall(String service, long remainingMs) {
        DistributionSummary.builder("deadline4j.remaining.at_call.ms")
            .tag("service", service)
            .register(registry)
            .record(remainingMs);
    }

    /** Record a circuit breaker activation due to high error rate. */
    public void recordCircuitOpen(String service) {
        Counter.builder("deadline4j.safety.circuit_open")
            .tag("service", service)
            .register(registry)
            .increment();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
