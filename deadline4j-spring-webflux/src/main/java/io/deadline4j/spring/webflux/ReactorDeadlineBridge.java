package io.deadline4j.spring.webflux;

import io.deadline4j.Deadline;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Bridges {@link Deadline} and Reactor's {@link Context}.
 *
 * <p>In a reactive pipeline there is no stable thread, so the deadline
 * is propagated via Reactor Context instead of a ThreadLocal.
 */
public final class ReactorDeadlineBridge {

    static final String CONTEXT_KEY = "io.deadline4j.Deadline";

    private ReactorDeadlineBridge() {}

    /**
     * Returns a new {@link Context} that contains the given deadline.
     *
     * @param ctx      the existing context
     * @param deadline the deadline to store
     * @return a new context containing the deadline
     */
    public static Context withDeadline(Context ctx, Deadline deadline) {
        return ctx.put(CONTEXT_KEY, deadline);
    }

    /**
     * Retrieves the deadline from a Reactor context, or {@code null} if absent.
     *
     * @param ctx the context to read from
     * @return the deadline, or null
     */
    public static Deadline fromContext(ContextView ctx) {
        return ctx.getOrDefault(CONTEXT_KEY, null);
    }
}
