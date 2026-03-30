package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineContextWrapTest {

    @AfterEach
    void cleanup() {
        DeadlineContext.clear().close();
    }

    @Test
    void wrapRunnable_capturesCurrentDeadlineAndPropagates() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "wrap-test");
        AtomicReference<Optional<Deadline>> seen = new AtomicReference<>();

        Runnable wrapped;
        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            wrapped = DeadlineContext.wrapRunnable(() -> seen.set(DeadlineContext.current()));
        }

        // Run on another thread — no deadline attached there
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            exec.submit(wrapped).get(2, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
        }

        assertThat(seen.get()).isPresent();
        assertThat(seen.get().get().id()).hasValue("wrap-test");
    }

    @Test
    void wrapRunnable_withNoDeadline_returnsOriginalTask() {
        // Ensure no deadline is set
        DeadlineContext.clear().close();

        Runnable original = () -> {};
        Runnable wrapped = DeadlineContext.wrapRunnable(original);

        assertThat(wrapped).isSameAs(original);
    }

    @Test
    void wrapCallable_capturesAndPropagatesDeadline() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "callable-test");

        Callable<Optional<Deadline>> wrapped;
        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            wrapped = DeadlineContext.wrapCallable(DeadlineContext::current);
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Optional<Deadline> result = exec.submit(wrapped).get(2, TimeUnit.SECONDS);
            assertThat(result).isPresent();
            assertThat(result.get().id()).hasValue("callable-test");
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void wrapCallable_withNoDeadline_returnsOriginalTask() {
        DeadlineContext.clear().close();

        Callable<String> original = () -> "hello";
        Callable<String> wrapped = DeadlineContext.wrapCallable(original);

        assertThat(wrapped).isSameAs(original);
    }

    @Test
    void wrappedTask_cleansUpDeadlineAfterExecution() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        AtomicReference<Optional<Deadline>> afterRun = new AtomicReference<>();

        Runnable wrapped;
        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            wrapped = DeadlineContext.wrapRunnable(() -> {
                // deadline is visible inside
            });
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // Run the wrapped task, then check the thread's context
            exec.submit(() -> {
                wrapped.run();
                afterRun.set(DeadlineContext.current());
            }).get(2, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
        }

        // After wrapped task completes, deadline should be cleaned up
        assertThat(afterRun.get()).isEmpty();
    }
}
