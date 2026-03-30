package io.deadline4j;

/**
 * Registry that resolves {@link ServiceConfig} for downstream services.
 *
 * <p>Delegates to a {@link DynamicServiceConfigSource} for the actual resolution.
 */
public final class ServiceConfigRegistry {

    private final DynamicServiceConfigSource configSource;

    /**
     * Creates a registry backed by the given config source.
     *
     * @param configSource source for per-service configuration
     */
    public ServiceConfigRegistry(DynamicServiceConfigSource configSource) {
        this.configSource = configSource;
    }

    /**
     * Returns the {@link ServiceConfig} for the named service.
     *
     * @param serviceName the downstream service name
     * @return configuration for that service
     */
    public ServiceConfig forService(String serviceName) {
        return configSource.resolve(serviceName);
    }
}
