package io.deadline4j;

/**
 * ThreadLocal-based storage. Correct for thread-per-request models.
 *
 * <p>Why not InheritableThreadLocal? It copies at thread creation,
 * not task submission. With thread pools (which reuse threads),
 * inherited values go stale. Explicit capture at task submission
 * and restore at execution is the only correct approach.
 */
final class ThreadLocalDeadlineContextStorage implements DeadlineContextStorage {

    private static final ThreadLocal<Deadline> CURRENT = new ThreadLocal<>();

    @Override
    public Deadline current() {
        return CURRENT.get();
    }

    @Override
    public DeadlineContext.Scope attach(Deadline deadline) {
        Deadline previous = CURRENT.get();
        CURRENT.set(deadline);
        return restoreScope(previous);
    }

    @Override
    public DeadlineContext.Scope clear() {
        Deadline previous = CURRENT.get();
        CURRENT.remove();
        return restoreScope(previous);
    }

    private DeadlineContext.Scope restoreScope(Deadline previous) {
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }
}
