package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineContextAwareExecutorServiceTest {

    private ExecutorService rawExecutor;
    private DeadlineContextAwareExecutorService executor;

    @BeforeEach
    void setUp() {
        rawExecutor = Executors.newFixedThreadPool(2);
        executor = new DeadlineContextAwareExecutorService(rawExecutor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        DeadlineContext.clear().close();
    }

    @Test
    void execute_propagatesDeadlineToWorkerThread() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "exec-test");
        AtomicReference<Optional<Deadline>> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            executor.execute(() -> {
                seen.set(DeadlineContext.current());
                latch.countDown();
            });
        }

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isPresent();
        assertThat(seen.get().get().id()).hasValue("exec-test");
    }

    @Test
    void submitRunnable_propagatesDeadline() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "submit-run");
        AtomicReference<Optional<Deadline>> seen = new AtomicReference<>();

        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            executor.submit((Runnable) () -> seen.set(DeadlineContext.current())).get(2, TimeUnit.SECONDS);
        }

        assertThat(seen.get()).isPresent();
        assertThat(seen.get().get().id()).hasValue("submit-run");
    }

    @Test
    void submitCallable_propagatesDeadline() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "submit-call");

        Optional<Deadline> result;
        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            result = executor.submit(DeadlineContext::current).get(2, TimeUnit.SECONDS);
        }

        assertThat(result).isPresent();
        assertThat(result.get().id()).hasValue("submit-call");
    }

    @Test
    void noDeadlineSet_tasksRunWithoutDeadline() throws Exception {
        // Ensure no deadline
        DeadlineContext.clear().close();

        Optional<Deadline> result = executor.submit(DeadlineContext::current).get(2, TimeUnit.SECONDS);
        assertThat(result).isEmpty();
    }

    @Test
    void invokeAll_propagatesDeadlineToAllTasks() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "invoke-all");

        List<Future<Optional<Deadline>>> futures;
        try (DeadlineContext.Scope s = DeadlineContext.attach(deadline)) {
            futures = executor.invokeAll(Arrays.asList(
                    DeadlineContext::current,
                    DeadlineContext::current
            ));
        }

        for (Future<Optional<Deadline>> f : futures) {
            Optional<Deadline> result = f.get(2, TimeUnit.SECONDS);
            assertThat(result).isPresent();
            assertThat(result.get().id()).hasValue("invoke-all");
        }
    }

    @Test
    void lifecycleMethods_delegateCorrectly() {
        assertThat(executor.isShutdown()).isFalse();
        assertThat(executor.isTerminated()).isFalse();

        executor.shutdown();
        assertThat(executor.isShutdown()).isTrue();
    }
}
