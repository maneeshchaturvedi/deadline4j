package io.deadline4j.spring.openfeign;

import io.deadline4j.DeadlineExceededException;

import java.lang.reflect.Proxy;

/**
 * Fallback factory for Feign clients. When a call is skipped due to
 * deadline exceeded or optional call degradation, returns a proxy
 * that produces sensible default values.
 *
 * @param <T> the Feign client interface type
 */
public class Deadline4jFallbackFactory<T> {

    private final Class<T> clientType;

    public Deadline4jFallbackFactory(Class<T> clientType) {
        this.clientType = clientType;
    }

    /**
     * Create a fallback instance for the given cause.
     * If the cause is a {@link DeadlineExceededException} (or subclass such as
     * {@link io.deadline4j.OptionalCallSkippedException}),
     * returns a proxy with default return values.
     * Otherwise rethrows.
     */
    @SuppressWarnings("unchecked")
    public T create(Throwable cause) {
        if (cause instanceof DeadlineExceededException) {
            return (T) Proxy.newProxyInstance(
                clientType.getClassLoader(),
                new Class[]{clientType},
                (proxy, method, args) -> DefaultReturnValues.forType(
                    method.getReturnType()));
        }
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        throw new RuntimeException(cause);
    }

    /** Returns the Feign client interface type this factory handles. */
    public Class<T> clientType() {
        return clientType;
    }
}
