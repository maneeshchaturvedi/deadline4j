package io.deadline4j;

/**
 * ThreadLocal-based budget storage. Correct for thread-per-request models.
 *
 * <p>Same rationale as {@link ThreadLocalDeadlineContextStorage} for
 * avoiding InheritableThreadLocal.
 */
final class ThreadLocalTimeoutBudgetStorage implements TimeoutBudgetStorage {

    private static final ThreadLocal<TimeoutBudget> CURRENT = new ThreadLocal<>();

    @Override
    public TimeoutBudget current() {
        return CURRENT.get();
    }

    @Override
    public DeadlineContext.Scope attach(TimeoutBudget budget) {
        TimeoutBudget previous = CURRENT.get();
        CURRENT.set(budget);
        return restoreScope(previous);
    }

    @Override
    public DeadlineContext.Scope clear() {
        TimeoutBudget previous = CURRENT.get();
        CURRENT.remove();
        return restoreScope(previous);
    }

    private DeadlineContext.Scope restoreScope(TimeoutBudget previous) {
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }
}
