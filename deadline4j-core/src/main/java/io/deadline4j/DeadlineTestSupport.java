package io.deadline4j;

import java.time.Duration;

/**
 * Utilities for unit testing code that uses deadline4j.
 * No Spring context required.
 *
 * <p>All methods attach/clear deadline context, run the provided block,
 * and restore the previous context on completion. Safe for nested use.
 */
public final class DeadlineTestSupport {

    private DeadlineTestSupport() {
        // utility class
    }

    /**
     * Run a block with a specific remaining duration attached as a deadline.
     *
     * @param remaining the duration until the deadline expires
     * @param block     the code to run within the deadline scope
     */
    public static void withDeadline(Duration remaining, Runnable block) {
        try (DeadlineContext.Scope ignored =
                     DeadlineContext.attach(Deadline.after(remaining))) {
            block.run();
        }
    }

    /**
     * Run a block with an already-expired deadline.
     *
     * @param block the code to run within the expired deadline scope
     */
    public static void withExpiredDeadline(Runnable block) {
        Deadline expired = Deadline.fromRemainingMillis(0);
        try (DeadlineContext.Scope ignored = DeadlineContext.attach(expired)) {
            block.run();
        }
    }

    /**
     * Run a block with no deadline (clean state). Any existing deadline
     * is temporarily cleared and restored after the block completes.
     *
     * @param block the code to run without any deadline
     */
    public static void withoutDeadline(Runnable block) {
        try (DeadlineContext.Scope ignored = DeadlineContext.clear()) {
            block.run();
        }
    }

    /**
     * Assert that a block completes within its budget. Sets a deadline
     * for the given budget, runs the block, and throws {@link AssertionError}
     * if the elapsed time exceeds the budget.
     *
     * @param budget the time budget
     * @param block  the code to run
     * @throws AssertionError if the block takes longer than the budget
     */
    public static void assertWithinBudget(Duration budget, Runnable block) {
        long start = System.nanoTime();
        withDeadline(budget, block);
        long elapsed = System.nanoTime() - start;
        if (elapsed > budget.toNanos()) {
            throw new AssertionError("Block exceeded budget: took "
                    + Duration.ofNanos(elapsed).toMillis() + "ms, budget was "
                    + budget.toMillis() + "ms");
        }
    }
}
