package io.deadline4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Tracks time budget consumption across sequential downstream calls
 * within a single request.
 *
 * <p>Created automatically by the inbound filter and stored in the
 * DeadlineContext. Interceptors record consumption on each outbound
 * call. Application code can access it for programmatic degradation
 * decisions when YAML configuration isn't sufficient.
 *
 * <p>Not thread-safe — single request thread or reactive chain.
 * For parallel fan-out, use the underlying Deadline directly
 * (immutable, thread-safe).
 *
 * <p>Segment tracking is sequential-only: segments are appended in
 * the order calls complete on a single thread.
 */
public final class TimeoutBudget {

    /** No-op budget: never expires, always affords, records nothing. */
    public static final TimeoutBudget NOOP = new TimeoutBudget(
        Deadline.after(Duration.ofDays(365)), true);

    private static volatile TimeoutBudgetStorage storage =
            TimeoutBudgetStorage.threadLocal();

    private final Deadline deadline;
    private final List<Segment> segments;
    private final boolean noop;

    private TimeoutBudget(Deadline deadline, boolean noop) {
        this.deadline = deadline;
        this.segments = noop ? Collections.emptyList() : new ArrayList<>();
        this.noop = noop;
    }

    /** Override the storage strategy. Call once at startup. */
    public static void setStorage(TimeoutBudgetStorage storageImpl) {
        Objects.requireNonNull(storageImpl, "storageImpl");
        storage = storageImpl;
    }

    /** Create a budget from an existing deadline. */
    public static TimeoutBudget from(Deadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        return new TimeoutBudget(deadline, false);
    }

    /** Create a budget that expires after the given duration. */
    public static TimeoutBudget of(Duration total) {
        Objects.requireNonNull(total, "total");
        return new TimeoutBudget(Deadline.after(total), false);
    }

    /**
     * Get the current budget, or NOOP if none is set.
     * This is the main entry point for application code AND interceptors.
     */
    public static TimeoutBudget current() {
        TimeoutBudget b = storage.current();
        return b != null ? b : NOOP;
    }

    /**
     * Attach a budget to the current context. Called by the inbound
     * filter (DeadlineFilter / DeadlineWebFilter) after creating the
     * Deadline. Returns a Scope for try-with-resources.
     */
    public static DeadlineContext.Scope attachBudget(TimeoutBudget budget) {
        Objects.requireNonNull(budget, "budget");
        return storage.attach(budget);
    }

    /** Time remaining. Never negative. */
    public Duration remaining() {
        return deadline.remaining();
    }

    /** True if this budget's deadline has expired. */
    public boolean isExpired() {
        return deadline.isExpired();
    }

    /** True if the remaining time is at least {@code estimate}. */
    public boolean canAfford(Duration estimate) {
        return remaining().compareTo(estimate) >= 0;
    }

    /**
     * Allocate time from the budget: returns the lesser of remaining
     * time and {@code maxAllocation}.
     */
    public Duration allocate(Duration maxAllocation) {
        Duration r = remaining();
        return r.compareTo(maxAllocation) < 0 ? r : maxAllocation;
    }

    /** Record consumption. Called by interceptors automatically. */
    public void recordConsumption(String callName, Duration consumed) {
        if (!noop) {
            segments.add(new Segment(callName, consumed));
        }
    }

    /** Unmodifiable view of recorded segments. */
    public List<Segment> segments() {
        return Collections.unmodifiableList(segments);
    }

    /** The underlying deadline. */
    public Deadline deadline() {
        return deadline;
    }

    /**
     * A recorded segment of budget consumption.
     */
    public static final class Segment {
        private final String name;
        private final Duration consumed;

        public Segment(String name, Duration consumed) {
            this.name = name;
            this.consumed = consumed;
        }

        /** Name of the downstream call. */
        public String name() {
            return name;
        }

        /** Duration consumed by the call. */
        public Duration consumed() {
            return consumed;
        }
    }
}
