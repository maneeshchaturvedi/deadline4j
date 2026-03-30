package io.deadline4j;

/**
 * Resolves {@link ServiceConfig} for a given service name.
 *
 * <p>Implementations may read from YAML configuration, a remote config
 * service, or any other source. Called by {@link ServiceConfigRegistry}.
 */
@FunctionalInterface
public interface DynamicServiceConfigSource {

    /**
     * Resolve the service configuration for the named service.
     *
     * @param serviceName the downstream service name
     * @return configuration for that service, never null
     */
    ServiceConfig resolve(String serviceName);
}
