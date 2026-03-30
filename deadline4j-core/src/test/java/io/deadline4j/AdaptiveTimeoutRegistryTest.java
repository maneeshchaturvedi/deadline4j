package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveTimeoutRegistryTest {

    private static final AdaptiveTimeoutConfig CONFIG_A = AdaptiveTimeoutConfig.builder()
            .coldStartTimeout(Duration.ofSeconds(1))
            .build();

    private static final AdaptiveTimeoutConfig CONFIG_B = AdaptiveTimeoutConfig.builder()
            .coldStartTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void forService_createsAndCachesAdaptiveTimeout() {
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> CONFIG_A);

        AdaptiveTimeout timeout = registry.forService("svc-a");

        assertThat(timeout).isNotNull();
        assertThat(timeout.name()).isEqualTo("svc-a");
    }

    @Test
    void forService_returnsSameInstanceForSameName() {
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> CONFIG_A);

        AdaptiveTimeout first = registry.forService("svc-a");
        AdaptiveTimeout second = registry.forService("svc-a");

        assertThat(first).isSameAs(second);
    }

    @Test
    void forService_returnsDifferentInstancesForDifferentNames() {
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> {
            if ("svc-a".equals(name)) return CONFIG_A;
            return CONFIG_B;
        });

        AdaptiveTimeout a = registry.forService("svc-a");
        AdaptiveTimeout b = registry.forService("svc-b");

        assertThat(a).isNotSameAs(b);
        assertThat(a.name()).isEqualTo("svc-a");
        assertThat(b.name()).isEqualTo("svc-b");
    }

    @Test
    void forService_fallsBackToDefaultsWhenConfigSourceThrows() {
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> {
            throw new RuntimeException("config unavailable");
        });

        AdaptiveTimeout timeout = registry.forService("svc-broken");

        // Should not throw; should fall back to default config
        assertThat(timeout).isNotNull();
        assertThat(timeout.name()).isEqualTo("svc-broken");
        // Default cold start timeout is 5s
        assertThat(timeout.currentTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void reloadConfig_replacesExistingInstance() {
        AdaptiveTimeoutConfig[] configHolder = { CONFIG_A };
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> configHolder[0]);

        AdaptiveTimeout original = registry.forService("svc-a");
        assertThat(original.currentTimeout()).isEqualTo(Duration.ofSeconds(1));

        // Change the config and reload
        configHolder[0] = CONFIG_B;
        registry.reloadConfig("svc-a");

        AdaptiveTimeout reloaded = registry.forService("svc-a");
        assertThat(reloaded).isNotSameAs(original);
        assertThat(reloaded.currentTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void reloadConfig_forUnknownServiceIsNoOp() {
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> CONFIG_A);

        // Should not throw
        registry.reloadConfig("unknown-service");

        // Verify it wasn't inadvertently created
        // Access it now — it should be created fresh
        AdaptiveTimeout timeout = registry.forService("unknown-service");
        assertThat(timeout).isNotNull();
    }

    @Test
    void forService_concurrentCallsFromMultipleThreads() throws InterruptedException {
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(name -> CONFIG_A);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        Set<AdaptiveTimeout> results = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    results.add(registry.forService("shared-service"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // All threads should have gotten the same instance
        assertThat(results).hasSize(1);
    }
}
