package io.deadline4j;

/**
 * Strategy for storing timeout budget context. Enables support for different
 * concurrency models without coupling the core to any framework.
 *
 * <p>Mirrors {@link DeadlineContextStorage} for budget tracking.
 */
public interface TimeoutBudgetStorage {

    /** Get the current budget, or null. */
    TimeoutBudget current();

    /**
     * Attach a budget. Returns a Scope that restores the previous
     * state on close. Must support nesting.
     */
    DeadlineContext.Scope attach(TimeoutBudget budget);

    /**
     * Clear the current budget. Returns a Scope that restores the
     * previous budget on close.
     */
    DeadlineContext.Scope clear();

    /** Default: ThreadLocal-based. */
    static TimeoutBudgetStorage threadLocal() {
        return new ThreadLocalTimeoutBudgetStorage();
    }
}
