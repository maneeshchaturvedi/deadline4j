package io.deadline4j;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Stores and retrieves the {@link Deadline} for the current request.
 *
 * <p>Storage is pluggable via {@link DeadlineContextStorage}. The
 * default is ThreadLocal-based. Reactive integrations bridge to
 * Reactor's Context. Vert.x integrations bridge to Vert.x local context.
 *
 * <p>This class is the primary entry point for application code that
 * needs to check remaining time (rare — most code never touches this).
 */
public final class DeadlineContext {

    private static volatile DeadlineContextStorage storage =
            DeadlineContextStorage.threadLocal();

    private DeadlineContext() {}

    /** Override the storage strategy. Call once at startup. */
    public static void setStorage(DeadlineContextStorage storageImpl) {
        Objects.requireNonNull(storageImpl, "storageImpl");
        storage = storageImpl;
    }

    /** Get the current deadline, if one is set. */
    public static Optional<Deadline> current() {
        return Optional.ofNullable(storage.current());
    }

    /**
     * Get remaining duration. Returns {@code defaultValue} if no
     * deadline is set.
     */
    public static Duration remaining(Duration defaultValue) {
        Deadline d = storage.current();
        return d != null ? d.remaining() : defaultValue;
    }

    /** True if a deadline is set and has expired. */
    public static boolean isExpired() {
        Deadline d = storage.current();
        return d != null && d.isExpired();
    }

    /**
     * Attach a deadline. Returns a {@link Scope} for try-with-resources.
     * Framework code (filters, interceptors) calls this.
     * Application code generally should not.
     */
    public static Scope attach(Deadline deadline) {
        if (deadline == null) {
            throw new NullPointerException("deadline");
        }
        return storage.attach(deadline);
    }

    /**
     * Clear the current deadline. Returns a Scope that restores the
     * previous deadline on close. Used by test utilities.
     */
    public static Scope clear() {
        return storage.clear();
    }

    /** Capture the current deadline for propagation to another thread. */
    public static Deadline capture() {
        return storage.current();
    }

    /**
     * Wraps a Runnable to capture the current deadline and restore it
     * during execution. The gRPC Context.wrap() equivalent.
     */
    public static Runnable wrapRunnable(Runnable task) {
        Deadline captured = capture();
        if (captured == null) return task;
        return () -> {
            try (Scope s = attach(captured)) {
                task.run();
            }
        };
    }

    /**
     * Wraps a Callable to capture the current deadline and restore it
     * during execution.
     */
    public static <V> Callable<V> wrapCallable(Callable<V> task) {
        Deadline captured = capture();
        if (captured == null) return task;
        return () -> {
            try (Scope s = attach(captured)) {
                return task.call();
            }
        };
    }

    /**
     * A closeable scope that restores the previous deadline on close.
     * Designed for use with try-with-resources.
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close(); // no checked exception
    }
}
