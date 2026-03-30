package io.deadline4j;

/**
 * Resolves {@link AdaptiveTimeoutConfig} for a given service name.
 *
 * <p>Implementations may read from YAML configuration, a remote config
 * service, or any other source. Called by {@link AdaptiveTimeoutRegistry}
 * when creating or reloading an {@link AdaptiveTimeout}.
 */
@FunctionalInterface
public interface DynamicConfigSource {

    /**
     * Resolve the adaptive timeout configuration for the named service.
     *
     * @param serviceName the downstream service name
     * @return configuration for that service, never null
     */
    AdaptiveTimeoutConfig resolve(String serviceName);
}
