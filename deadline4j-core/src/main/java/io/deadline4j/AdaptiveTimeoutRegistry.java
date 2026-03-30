package io.deadline4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of {@link AdaptiveTimeout} instances, keyed by service name.
 *
 * <p>Thread-safe. Uses {@link ConcurrentHashMap#computeIfAbsent} to ensure
 * each service name maps to exactly one {@link AdaptiveTimeout} instance.
 *
 * <p>If the {@link DynamicConfigSource} throws when resolving config,
 * a default configuration is used as fallback.
 */
public final class AdaptiveTimeoutRegistry {

    private static final Logger LOG = Logger.getLogger(AdaptiveTimeoutRegistry.class.getName());

    private final ConcurrentHashMap<String, AdaptiveTimeout> timeouts = new ConcurrentHashMap<>();
    private final DynamicConfigSource configSource;

    /**
     * Creates a registry backed by the given config source.
     *
     * @param configSource source for per-service adaptive timeout configuration
     */
    public AdaptiveTimeoutRegistry(DynamicConfigSource configSource) {
        this.configSource = configSource;
    }

    /**
     * Returns the {@link AdaptiveTimeout} for the given service, creating it
     * on first access. Subsequent calls return the cached instance.
     *
     * <p>If the config source throws, a default configuration is used.
     *
     * @param name the service name
     * @return the adaptive timeout for that service
     */
    public AdaptiveTimeout forService(String name) {
        return timeouts.computeIfAbsent(name, this::createTimeout);
    }

    /**
     * Reloads configuration for the named service by replacing its
     * {@link AdaptiveTimeout} with a freshly configured instance.
     *
     * <p>No-op if the service is not already registered.
     *
     * @param serviceName the service whose config should be reloaded
     */
    public void reloadConfig(String serviceName) {
        timeouts.computeIfPresent(serviceName, (key, old) -> createTimeout(key));
    }

    private AdaptiveTimeout createTimeout(String name) {
        AdaptiveTimeoutConfig config;
        try {
            config = configSource.resolve(name);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Failed to resolve config for service '" + name
                            + "', falling back to defaults", e);
            config = AdaptiveTimeoutConfig.builder().build();
        }
        return new AdaptiveTimeout(name, config);
    }
}
