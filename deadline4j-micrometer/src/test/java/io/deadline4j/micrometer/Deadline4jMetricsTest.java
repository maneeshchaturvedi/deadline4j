package io.deadline4j.micrometer;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class Deadline4jMetricsTest {

    private SimpleMeterRegistry registry;
    private Deadline4jMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new Deadline4jMetrics(registry);
    }

    @Test
    void recordCallDuration_createsTimerWithCorrectTags() {
        metrics.recordCallDuration("payment-svc", "success", Duration.ofMillis(150));

        Timer timer = registry.find("deadline4j.call.duration")
                .tag("service", "payment-svc")
                .tag("outcome", "success")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(150.0);
    }

    @Test
    void registerAdaptiveTimeoutGauge_registersGaugeThatReturnsSupplierValue() {
        AtomicLong timeout = new AtomicLong(500);
        metrics.registerAdaptiveTimeoutGauge("order-svc", timeout::get);

        Gauge gauge = registry.find("deadline4j.adaptive.timeout.ms")
                .tag("service", "order-svc")
                .gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(500.0);

        timeout.set(750);
        assertThat(gauge.value()).isEqualTo(750.0);
    }

    @Test
    void registerAdaptivePercentileGauge_registersGauge() {
        AtomicLong percentile = new AtomicLong(420);
        metrics.registerAdaptivePercentileGauge("inventory-svc", percentile::get);

        Gauge gauge = registry.find("deadline4j.adaptive.percentile.ms")
                .tag("service", "inventory-svc")
                .gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(420.0);

        percentile.set(380);
        assertThat(gauge.value()).isEqualTo(380.0);
    }

    @Test
    void recordBudgetConsumedRatio_createsDistributionSummary() {
        metrics.recordBudgetConsumedRatio(0.75);
        metrics.recordBudgetConsumedRatio(1.2);

        DistributionSummary summary = registry.find("deadline4j.budget.consumed.ratio")
                .summary();

        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualTo(0.75 + 1.2);
    }

    @Test
    void recordDeadlineExceeded_incrementsCounterWithTags() {
        metrics.recordDeadlineExceeded("payment-svc", "during_call", "enforce");
        metrics.recordDeadlineExceeded("payment-svc", "during_call", "enforce");

        Counter counter = registry.find("deadline4j.deadline.exceeded")
                .tag("service", "payment-svc")
                .tag("phase", "during_call")
                .tag("mode", "enforce")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordCallSkipped_incrementsCounter() {
        metrics.recordCallSkipped("recommendations-svc");

        Counter counter = registry.find("deadline4j.call.skipped")
                .tag("service", "recommendations-svc")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordRemainingAtCall_recordsToDistributionSummary() {
        metrics.recordRemainingAtCall("auth-svc", 300);
        metrics.recordRemainingAtCall("auth-svc", 150);

        DistributionSummary summary = registry.find("deadline4j.remaining.at_call.ms")
                .tag("service", "auth-svc")
                .summary();

        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualTo(450.0);
    }

    @Test
    void multipleServicesTrackedSeparately() {
        metrics.recordCallSkipped("svc-a");
        metrics.recordCallSkipped("svc-a");
        metrics.recordCallSkipped("svc-b");

        Counter counterA = registry.find("deadline4j.call.skipped")
                .tag("service", "svc-a")
                .counter();
        Counter counterB = registry.find("deadline4j.call.skipped")
                .tag("service", "svc-b")
                .counter();

        assertThat(counterA).isNotNull();
        assertThat(counterA.count()).isEqualTo(2.0);
        assertThat(counterB).isNotNull();
        assertThat(counterB.count()).isEqualTo(1.0);
    }

    @Test
    void recordCircuitOpen_incrementsCounter() {
        metrics.recordCircuitOpen("payment-svc");
        metrics.recordCircuitOpen("payment-svc");

        Counter counter = registry.find("deadline4j.safety.circuit_open")
                .tag("service", "payment-svc")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void registry_returnsMeterRegistry() {
        assertThat(metrics.registry()).isSameAs(registry);
    }
}
