package io.deadline4j.it.core;

import io.deadline4j.Deadline;
import io.deadline4j.TimeoutBudget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Deadline (immutable) is safe to share across threads
 * during parallel fan-out, while TimeoutBudget segments are recorded
 * sequentially on the main thread.
 */
class ParallelFanOutSafetyTest {

    @Test
    void deadlineIsThreadSafeDuringParallelFanOut() throws InterruptedException {
        Deadline deadline = Deadline.after(Duration.ofMillis(5000), "fan-out-req");
        TimeoutBudget budget = TimeoutBudget.from(deadline);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch doneLatch = new CountDownLatch(3);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Fork 3 threads, each reading the shared (immutable) Deadline
        for (int i = 0; i < 3; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    // Read remaining from the shared deadline
                    long remaining = deadline.remainingMillis();
                    if (remaining <= 0 || remaining > 5100) {
                        errorCount.incrementAndGet();
                    }

                    // Verify ID is accessible
                    String id = deadline.id().orElse("");
                    if (!"fan-out-req".equals(id)) {
                        errorCount.incrementAndGet();
                    }

                    // Verify not expired
                    if (deadline.isExpired()) {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errorCount.get()).isZero();

        // Main thread records 3 budget segments sequentially
        budget.recordConsumption("downstream-1", Duration.ofMillis(100));
        budget.recordConsumption("downstream-2", Duration.ofMillis(200));
        budget.recordConsumption("downstream-3", Duration.ofMillis(150));

        assertThat(budget.segments()).hasSize(3);
        assertThat(budget.segments().get(0).name()).isEqualTo("downstream-1");
        assertThat(budget.segments().get(1).name()).isEqualTo("downstream-2");
        assertThat(budget.segments().get(2).name()).isEqualTo("downstream-3");

        // Verify total consumption recorded
        long totalConsumed = budget.segments().stream()
                .mapToLong(s -> s.consumed().toMillis())
                .sum();
        assertThat(totalConsumed).isEqualTo(450L);
    }
}
