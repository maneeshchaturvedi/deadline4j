package io.deadline4j;

import java.net.URI;

/**
 * Maps a target (e.g. URI, hostname, request object) to a logical service name.
 *
 * @param <T> the target type
 */
@FunctionalInterface
public interface ServiceNameResolver<T> {

    /**
     * Resolve the logical service name for the given target.
     *
     * @param target the target to resolve
     * @return the service name
     */
    String resolve(T target);

    /**
     * Returns a resolver that extracts the host from a {@link URI}.
     *
     * @return a URI-based service name resolver
     */
    static ServiceNameResolver<URI> byHost() {
        return uri -> uri.getHost();
    }
}
