package io.deadline4j;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * An absolute point on the monotonic clock by which work must complete.
 *
 * <p>Based on {@link System#nanoTime()}, never wall-clock time. Immune
 * to NTP adjustments. The wire protocol transmits remaining duration,
 * not absolute time, so clock skew between services is irrelevant.
 *
 * <p>Thread-safe and immutable.
 */
public final class Deadline implements Comparable<Deadline> {

    /**
     * Maximum remaining millis accepted by {@link #fromRemainingMillis(long, String)}.
     * Caps at 5 minutes to prevent nanoTime overflow when converting to nanos.
     */
    private static final long MAX_REMAINING_MS = TimeUnit.MINUTES.toMillis(5); // 300,000ms

    private final long deadlineNanos; // System.nanoTime() value
    private final String id;          // nullable correlation ID

    private Deadline(long deadlineNanos, String id) {
        this.deadlineNanos = deadlineNanos;
        this.id = id;
    }

    /** Create a deadline that expires {@code duration} from now. */
    public static Deadline after(Duration duration) {
        return after(duration, null);
    }

    /** Create a deadline with a correlation ID. */
    public static Deadline after(Duration duration, String id) {
        Objects.requireNonNull(duration, "duration");
        long nanos = duration.isNegative() ? 0 : duration.toNanos();
        return new Deadline(System.nanoTime() + nanos, id);
    }

    /**
     * Reconstruct from a remaining-millis value received over the wire.
     * Anchors to the current monotonic clock.
     */
    public static Deadline fromRemainingMillis(long remainingMs) {
        return fromRemainingMillis(remainingMs, null);
    }

    /**
     * Reconstruct from a remaining-millis value received over the wire.
     * Anchors to the current monotonic clock.
     *
     * <p>The value is capped at {@value #MAX_REMAINING_MS}ms to prevent
     * overflow when converting to nanos.
     *
     * @param remainingMs remaining time in milliseconds
     * @param id optional correlation ID
     * @return a new deadline anchored to the monotonic clock
     */
    public static Deadline fromRemainingMillis(long remainingMs, String id) {
        long cappedMs = Math.min(Math.max(remainingMs, 0), MAX_REMAINING_MS);
        return new Deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cappedMs), id);
    }

    /** Time remaining. Never negative — returns {@link Duration#ZERO} if expired. */
    public Duration remaining() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 ? Duration.ofNanos(remaining) : Duration.ZERO;
    }

    /** Remaining time in millis. Returns 0 if expired. */
    public long remainingMillis() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 ? TimeUnit.NANOSECONDS.toMillis(remaining) : 0;
    }

    /** True if this deadline has expired. */
    public boolean isExpired() {
        return System.nanoTime() >= deadlineNanos;
    }

    /**
     * Returns the earlier (more restrictive) of this deadline and
     * {@code other}. Mirrors gRPC's effectiveDeadline =
     * min(callOptions.deadline, context.deadline).
     */
    public Deadline min(Deadline other) {
        Objects.requireNonNull(other, "other");
        return this.deadlineNanos <= other.deadlineNanos ? this : other;
    }

    /** Correlation ID, if set. */
    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    /**
     * Registers a listener that fires when this deadline expires.
     * If already expired, fires immediately on the calling thread.
     * The listener is called at most once.
     *
     * @param listener the action to run on expiration
     * @param timer    the timer used to schedule the callback
     * @return a TimerHandle that can be used to cancel the listener
     */
    public DeadlineTimer.TimerHandle onExpiration(Runnable listener, DeadlineTimer timer) {
        if (isExpired()) {
            listener.run();
            return () -> false; // already fired
        }
        long delayNanos = deadlineNanos - System.nanoTime();
        if (delayNanos <= 0) {
            listener.run();
            return () -> false;
        }
        return timer.schedule(listener, delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Deadline other) {
        return Long.compare(this.deadlineNanos, other.deadlineNanos);
    }

    @Override
    public String toString() {
        return "Deadline[remaining=" + remainingMillis() + "ms"
                + (id != null ? ", id=" + id : "") + "]";
    }
}
