package io.deadline4j.it.core;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that DeadlineContext provides per-thread isolation with no
 * cross-contamination across concurrent requests.
 */
class ConcurrentRequestIsolationTest {

    @Test
    void fiftyThreadsEachSeeTheirOwnDeadline() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final long expectedMs = (index + 1) * 100L; // 100ms to 5000ms

            executor.submit(() -> {
                try {
                    startLatch.await();

                    Deadline deadline = Deadline.after(Duration.ofMillis(expectedMs),
                            "thread-" + index);

                    try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
                        // Small yield to increase chance of interleaving
                        Thread.yield();

                        // Verify own deadline
                        Deadline current = DeadlineContext.current().orElse(null);
                        if (current == null) {
                            errorCount.incrementAndGet();
                            return;
                        }

                        // Check the ID matches
                        String id = current.id().orElse("");
                        if (!id.equals("thread-" + index)) {
                            errorCount.incrementAndGet();
                            return;
                        }

                        // Check remaining is within reasonable range
                        long remaining = current.remainingMillis();
                        if (remaining <= 0 || remaining > expectedMs + 50) {
                            errorCount.incrementAndGet();
                        }
                    }

                    // After scope close, verify deadline is cleared
                    if (DeadlineContext.current().isPresent()) {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errorCount.get()).isZero();
    }
}
