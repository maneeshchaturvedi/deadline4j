package io.deadline4j;

/**
 * Strategy for storing deadline context. Enables support for different
 * concurrency models without coupling the core to any framework.
 *
 * <p>Implementations:
 * <ul>
 *   <li>ThreadLocal (default) — servlet containers, Kafka consumers</li>
 *   <li>Reactor Context bridge — WebFlux (provided by deadline4j-spring-webflux)</li>
 *   <li>Vert.x local context — (future module)</li>
 *   <li>ScopedValue — Java 21+ structured concurrency (future)</li>
 * </ul>
 */
public interface DeadlineContextStorage {

    /** Get the current deadline, or null. */
    Deadline current();

    /**
     * Attach a deadline. Returns a Scope that restores the previous
     * state on close. Must support nesting.
     */
    DeadlineContext.Scope attach(Deadline deadline);

    /**
     * Clear the current deadline. Returns a Scope that restores the
     * previous deadline on close.
     */
    DeadlineContext.Scope clear();

    /** Default: ThreadLocal-based. */
    static DeadlineContextStorage threadLocal() {
        return new ThreadLocalDeadlineContextStorage();
    }
}
