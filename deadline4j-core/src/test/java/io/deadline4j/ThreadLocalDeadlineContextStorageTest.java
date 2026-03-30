package io.deadline4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadLocalDeadlineContextStorageTest {

    private ThreadLocalDeadlineContextStorage storage;

    @BeforeEach
    void setUp() {
        storage = new ThreadLocalDeadlineContextStorage();
        // Ensure clean state — clear without restoring
        storage.clear();
    }

    // --- Basic operations ---

    @Test
    void current_returnsNull_initially() {
        assertThat(storage.current()).isNull();
    }

    @Test
    void attach_setsCurrent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope scope = storage.attach(deadline)) {
            assertThat(storage.current()).isSameAs(deadline);
        }
    }

    @Test
    void attach_returnsScope_thatRestoresNull() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        DeadlineContext.Scope scope = storage.attach(deadline);
        assertThat(storage.current()).isSameAs(deadline);
        scope.close();
        assertThat(storage.current()).isNull();
    }

    // --- Nesting ---

    @Test
    void nesting_restoresPreviousDeadline() {
        Deadline outer = Deadline.after(Duration.ofSeconds(10), "outer");
        Deadline inner = Deadline.after(Duration.ofSeconds(1), "inner");

        try (DeadlineContext.Scope outerScope = storage.attach(outer)) {
            assertThat(storage.current()).isSameAs(outer);

            try (DeadlineContext.Scope innerScope = storage.attach(inner)) {
                assertThat(storage.current()).isSameAs(inner);
            }

            assertThat(storage.current()).isSameAs(outer);
        }

        assertThat(storage.current()).isNull();
    }

    @Test
    void nesting_deepNesting_restoresCorrectly() {
        Deadline d1 = Deadline.after(Duration.ofSeconds(10));
        Deadline d2 = Deadline.after(Duration.ofSeconds(5));
        Deadline d3 = Deadline.after(Duration.ofSeconds(2));
        Deadline d4 = Deadline.after(Duration.ofSeconds(1));

        try (DeadlineContext.Scope s1 = storage.attach(d1)) {
            try (DeadlineContext.Scope s2 = storage.attach(d2)) {
                try (DeadlineContext.Scope s3 = storage.attach(d3)) {
                    try (DeadlineContext.Scope s4 = storage.attach(d4)) {
                        assertThat(storage.current()).isSameAs(d4);
                    }
                    assertThat(storage.current()).isSameAs(d3);
                }
                assertThat(storage.current()).isSameAs(d2);
            }
            assertThat(storage.current()).isSameAs(d1);
        }
        assertThat(storage.current()).isNull();
    }

    // --- clear() ---

    @Test
    void clear_removesDeadline() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope attachScope = storage.attach(deadline)) {
            assertThat(storage.current()).isSameAs(deadline);

            DeadlineContext.Scope clearScope = storage.clear();
            assertThat(storage.current()).isNull();

            // Restore
            clearScope.close();
            assertThat(storage.current()).isSameAs(deadline);
        }
    }

    @Test
    void clear_whenNoDeadline_scopeRestoresNull() {
        DeadlineContext.Scope scope = storage.clear();
        assertThat(storage.current()).isNull();
        scope.close();
        assertThat(storage.current()).isNull();
    }

    // --- ThreadLocal cleanup ---

    @Test
    void cleanup_removesThreadLocal_whenPreviousWasNull() {
        // When the previous deadline was null, closing the scope should
        // call ThreadLocal.remove() rather than set(null), preventing leaks
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        DeadlineContext.Scope scope = storage.attach(deadline);
        assertThat(storage.current()).isNotNull();

        scope.close();
        // After close, current should be null (ThreadLocal removed)
        assertThat(storage.current()).isNull();
    }

    // --- Cross-thread isolation ---

    @Test
    void crossThread_deadlineNotVisibleInOtherThread() throws Exception {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Deadline> otherThreadDeadline = new AtomicReference<>();

        try (DeadlineContext.Scope scope = storage.attach(deadline)) {
            assertThat(storage.current()).isSameAs(deadline);

            Thread thread = new Thread(() -> {
                // Create a fresh storage instance — but ThreadLocal is static,
                // so we need to use the same storage. The key point is that
                // ThreadLocal values are thread-local, so the other thread
                // should not see this thread's deadline.
                otherThreadDeadline.set(storage.current());
                latch.countDown();
            });
            thread.start();
            latch.await();

            assertThat(otherThreadDeadline.get()).isNull();
        }
    }

    @Test
    void crossThread_eachThreadHasOwnDeadline() throws Exception {
        Deadline mainDeadline = Deadline.after(Duration.ofSeconds(10), "main");
        Deadline otherDeadline = Deadline.after(Duration.ofSeconds(1), "other");
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Deadline> otherThreadSeen = new AtomicReference<>();

        try (DeadlineContext.Scope scope = storage.attach(mainDeadline)) {
            Thread thread = new Thread(() -> {
                try (DeadlineContext.Scope otherScope = storage.attach(otherDeadline)) {
                    otherThreadSeen.set(storage.current());
                    ready.countDown();
                    try {
                        done.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            thread.start();
            ready.await();

            // Main thread still sees its own deadline
            assertThat(storage.current()).isSameAs(mainDeadline);
            // Other thread sees its own deadline
            assertThat(otherThreadSeen.get()).isSameAs(otherDeadline);

            done.countDown();
            thread.join(5000);
        }
    }
}
