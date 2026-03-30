# deadline4j — Design Document v2

**Zero-code deadline propagation, adaptive timeouts, and timeout budgets for Spring applications.**

*Java 11+ · Spring Boot 2.7+ / 3.x · Micrometer · OpenTelemetry*

---

## 1. Problem Statement

In a Spring-based microservice architecture, timeout management is fragmented. RestTemplate, WebClient, and Feign each configure timeouts independently. No mechanism propagates a caller's deadline downstream. No library tracks how much of a request's time budget has been consumed across sequential calls. And fixed timeouts bear no relationship to actual service behavior.

gRPC solves this natively: a single `withDeadlineAfter(5, SECONDS)` at the edge propagates through every hop, is enforced automatically, cascades cancellation on expiry, and requires zero application code at intermediate services. Spring has no equivalent.

This library brings gRPC's zero-code deadline model to the Spring HTTP ecosystem.

### 1.1 Design Principles

1. **Zero code at call sites.** Propagation, enforcement, budget tracking, and degradation happen in interceptors and filters. Application code is unchanged.
2. **Configuration-driven degradation.** Whether a service call is required or optional, and what happens when budget is insufficient, is a YAML concern, not a code concern.
3. **Safe by default.** The library ships in `observe` mode. It tracks and emits metrics but enforces nothing until explicitly switched to `enforce`. Bad configuration cannot cause an outage without an explicit opt-in.
4. **Incremental adoption.** Deploy to all services at once. Enable enforcement per-service, per-environment, at your own pace. Services without the library simply ignore the deadline header.
5. **Framework-agnostic core.** The core module has zero framework dependencies. Spring, Quarkus, Vert.x, and messaging integrations are separate modules.
6. **Escape hatch for fine-tuning.** For the 10% of cases where YAML config isn't enough, the `TimeoutBudget` programmatic API is available as opt-in.

---

## 2. Module Structure

```
deadline4j/
├── deadline4j-bom/                         Maven BOM for version alignment
├── deadline4j-core/                         Zero-dependency core (Java 11+, + HdrHistogram)
├── deadline4j-spring-boot-starter/          Auto-configuration for Spring Boot
├── deadline4j-spring-webmvc-javax/           Servlet filter + RestTemplate interceptor (Boot 2.x)
├── deadline4j-spring-webmvc-jakarta/         Servlet filter + RestTemplate interceptor (Boot 3.x)
├── deadline4j-spring-webflux/               WebFilter + WebClient filter + Reactor bridge
├── deadline4j-spring-cloud-openfeign/       Feign interceptor + fallback factory
├── deadline4j-micrometer/                   Metrics via Micrometer
├── deadline4j-opentelemetry/                OTel span attributes + baggage propagation
├── deadline4j-timer-netty/                  HashedWheelTimer adapter (optional)
└── deadline4j-timer-agrona/                 DeadlineTimerWheel adapter (optional)
```

### Dependency Graph

```
deadline4j-core  (Java 11 stdlib + HdrHistogram 2.1.12. Nothing else.)
       ↑
       ├── deadline4j-spring-webmvc          (+ spring-webmvc, spring-web)
       ├── deadline4j-spring-webflux         (+ spring-webflux, reactor-core)
       ├── deadline4j-spring-cloud-openfeign (+ spring-cloud-openfeign)
       ├── deadline4j-micrometer             (+ micrometer-core)
       ├── deadline4j-opentelemetry          (+ opentelemetry-api)
       ├── deadline4j-timer-netty            (+ netty-common)
       └── deadline4j-timer-agrona           (+ agrona)
              ↑
              └── deadline4j-spring-boot-starter (conditional auto-config pulls
                                                  relevant modules based on classpath)
```

---

## 3. Core API — `deadline4j-core`

### 3.1 Wire Protocol: HTTP Header Contract

```
X-Deadline-Remaining-Ms: 1450
X-Deadline-Id: txn-abc-123
```

**Why remaining duration, not absolute timestamp?** Clock skew between hosts makes absolute timestamps unreliable. gRPC propagates `grpc-timeout` as remaining duration for the same reason. The receiving service records `System.nanoTime()` at parse time and computes a local deadline from there — monotonic clock, immune to NTP adjustments.

**Why not Hybrid Logical Clocks (HLCs)?** HLCs solve causal ordering ("did A happen before B?"), not duration measurement ("how much wall-clock time remains?"). The logical counter can advance the HLC beyond physical time under message bursts, causing false expirations. Physical clock skew is bounded but not eliminated — on a 500ms deadline, 100ms of skew is 20% error. The wire format is pluggable via `DeadlineCodec` (§3.8) for teams with existing HLC infrastructure, but remaining-duration is the correct default.

`X-Deadline-Id` is optional. It enables cross-service correlation in logs and traces.

### 3.2 `Deadline`

The fundamental unit. Immutable, monotonic-clock-based, safe for concurrent access.

```java
package io.deadline4j;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * An absolute point on the monotonic clock by which work must complete.
 *
 * <p>Based on {@link System#nanoTime()}, never wall-clock time. Immune
 * to NTP adjustments. The wire protocol transmits remaining duration,
 * not absolute time, so clock skew between services is irrelevant.
 *
 * <p>Thread-safe and immutable.
 */
public final class Deadline implements Comparable<Deadline> {

    private final long deadlineNanos; // System.nanoTime() value
    private final String id;         // nullable correlation ID

    private Deadline(long deadlineNanos, String id) {
        this.deadlineNanos = deadlineNanos;
        this.id = id;
    }

    /** Create a deadline that expires {@code duration} from now. */
    public static Deadline after(Duration duration) {
        return after(duration, null);
    }

    /** Create a deadline with a correlation ID. */
    public static Deadline after(Duration duration, String id) {
        return new Deadline(System.nanoTime() + duration.toNanos(), id);
    }

    /**
     * Reconstruct from a remaining-millis value received over the wire.
     * Anchors to the current monotonic clock.
     */
    public static Deadline fromRemainingMillis(long remainingMs) {
        return fromRemainingMillis(remainingMs, null);
    }

    public static Deadline fromRemainingMillis(long remainingMs, String id) {
        return new Deadline(
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs), id);
    }

    /** Time remaining. Never negative — returns Duration.ZERO if expired. */
    public Duration remaining() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 ? Duration.ofNanos(remaining) : Duration.ZERO;
    }

    /** Remaining time in millis. Returns 0 if expired. */
    public long remainingMillis() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 ? TimeUnit.NANOSECONDS.toMillis(remaining) : 0;
    }

    public boolean isExpired() {
        return System.nanoTime() >= deadlineNanos;
    }

    /**
     * Returns the earlier (more restrictive) of this deadline and
     * {@code other}. Mirrors gRPC's effectiveDeadline =
     * min(callOptions.deadline, context.deadline).
     */
    public Deadline min(Deadline other) {
        return this.deadlineNanos <= other.deadlineNanos ? this : other;
    }

    /** Correlation ID, if set. */
    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    @Override
    public int compareTo(Deadline other) {
        return Long.compare(this.deadlineNanos, other.deadlineNanos);
    }

    @Override
    public String toString() {
        return "Deadline[remaining=" + remainingMillis() + "ms"
            + (id != null ? ", id=" + id : "") + "]";
    }
}
```

**Key decisions:**
- `remaining()` clamps to `Duration.ZERO` — callers never see negative durations.
- `min()` mirrors gRPC's `effectiveDeadline = min(explicit, inherited)` — the more restrictive deadline always wins.
- `toString()` shows remaining time, not the raw nanoTime, for debuggability.

### 3.3 `DeadlineContext`

Stores the current request's deadline. Mirrors `io.grpc.Context` for deadline carriage.

```java
package io.deadline4j;

import java.time.Duration;
import java.util.Optional;

/**
 * Stores and retrieves the {@link Deadline} for the current request.
 *
 * <p>Storage is pluggable via {@link DeadlineContextStorage}. The
 * default is ThreadLocal-based. Reactive integrations bridge to
 * Reactor's Context. Vert.x integrations bridge to Vert.x local context.
 *
 * <p>This class is the primary entry point for application code that
 * needs to check remaining time (rare — most code never touches this).
 */
public final class DeadlineContext {

    private static volatile DeadlineContextStorage storage =
        DeadlineContextStorage.threadLocal();

    private DeadlineContext() {}

    /** Override the storage strategy. Call once at startup. */
    public static void setStorage(DeadlineContextStorage storageImpl) {
        storage = storageImpl;
    }

    /** Get the current deadline, if one is set. */
    public static Optional<Deadline> current() {
        return Optional.ofNullable(storage.current());
    }

    /**
     * Get remaining duration. Returns {@code defaultValue} if no
     * deadline is set.
     */
    public static Duration remaining(Duration defaultValue) {
        Deadline d = storage.current();
        return d != null ? d.remaining() : defaultValue;
    }

    /** True if a deadline is set and has expired. */
    public static boolean isExpired() {
        Deadline d = storage.current();
        return d != null && d.isExpired();
    }

    /**
     * Attach a deadline. Returns a {@link Scope} for try-with-resources.
     * Framework code (filters, interceptors) calls this.
     * Application code generally should not.
     */
    public static Scope attach(Deadline deadline) {
        if (deadline == null) throw new NullPointerException("deadline");
        return storage.attach(deadline);
    }

    /**
     * Clear the current deadline. Returns a Scope that restores the
     * previous deadline on close. Used by test utilities.
     */
    public static Scope clear() {
        return storage.clear();
    }

    /** Capture the current deadline for propagation to another thread. */
    public static Deadline capture() {
        return storage.current();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close(); // no checked exception
    }
}
```

### 3.4 `DeadlineContextStorage` — Pluggable Storage SPI

```java
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
```

```java
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
```

### 3.5 `AdaptiveTimeout`

Tracks observed latencies for a service and computes dynamic timeouts.

```java
package io.deadline4j;

import java.time.Duration;

/**
 * Computes dynamic timeouts based on observed latency distributions.
 *
 * <p>Backed by a sliding-window HdrHistogram. The timeout is set at a
 * configurable percentile of observed latencies, multiplied by a
 * headroom factor, and clamped to absolute bounds.
 *
 * <p>Thread-safe. One instance per downstream service, shared across
 * all request threads.
 */
public final class AdaptiveTimeout {

    private final String name;
    private final AdaptiveTimeoutConfig config;
    private final SlidingWindowHistogram histogram;

    AdaptiveTimeout(String name, AdaptiveTimeoutConfig config) {
        this.name = name;
        this.config = config;
        this.histogram = new SlidingWindowHistogram(
            config.windowSize().toNanos(),
            config.maxTimeout().toMillis() * 2  // track beyond max for distribution visibility
        );
    }

    /** Record an observed latency. Called by interceptors, not application code. */
    public void recordLatency(Duration latency) {
        histogram.recordValue(latency.toMillis());
    }

    /**
     * Current adaptive timeout value.
     *
     * <p>During cold start (fewer than {@code minSamples} observations),
     * returns {@code coldStartTimeout}.
     *
     * <p>After warm-up: percentile × headroomMultiplier, clamped to
     * [minTimeout, maxTimeout].
     */
    public Duration currentTimeout() {
        if (histogram.totalCount() < config.minSamples()) {
            return config.coldStartTimeout();
        }
        long percentileMs = histogram.getValueAtPercentile(
            config.percentile() * 100.0);
        long withHeadroom = (long) (percentileMs * config.headroomMultiplier());
        long clamped = Math.max(config.minTimeout().toMillis(),
            Math.min(withHeadroom, config.maxTimeout().toMillis()));
        return Duration.ofMillis(clamped);
    }

    /**
     * Effective timeout: min(adaptive timeout, remaining deadline).
     * This is what interceptors use. Mirrors gRPC's
     * effectiveDeadline = min(callOptions.deadline, context.deadline).
     */
    public Duration effectiveTimeout(Deadline deadline) {
        Duration adaptive = currentTimeout();
        Duration remaining = deadline.remaining();
        return remaining.compareTo(adaptive) < 0 ? remaining : adaptive;
    }

    /** Raw percentile value in millis (before headroom). For observability. */
    public long currentPercentileMillis() {
        return histogram.getValueAtPercentile(config.percentile() * 100.0);
    }

    public long sampleCount() { return histogram.totalCount(); }
    public String name() { return name; }
}
```

```java
package io.deadline4j;

import java.time.Duration;

/**
 * Configuration for {@link AdaptiveTimeout}. Immutable.
 */
public final class AdaptiveTimeoutConfig {

    // Absolute safety bounds. Cannot be overridden by configuration.
    private static final Duration ABSOLUTE_MIN = Duration.ofMillis(1);
    private static final Duration ABSOLUTE_MAX = Duration.ofMinutes(5);

    private final double percentile;          // Default: 0.99 (P99)
    private final Duration minTimeout;        // Default: 50ms
    private final Duration maxTimeout;        // Default: 30s
    private final Duration coldStartTimeout;  // Default: 5s
    private final int minSamples;             // Default: 100
    private final Duration windowSize;        // Default: 60s
    private final double headroomMultiplier;  // Default: 1.5

    private AdaptiveTimeoutConfig(Builder b) {
        this.percentile = b.percentile;
        this.minTimeout = clamp(b.minTimeout, ABSOLUTE_MIN, ABSOLUTE_MAX);
        this.maxTimeout = clamp(b.maxTimeout, ABSOLUTE_MIN, ABSOLUTE_MAX);
        this.coldStartTimeout = clamp(b.coldStartTimeout, ABSOLUTE_MIN, ABSOLUTE_MAX);
        this.minSamples = Math.max(1, b.minSamples);
        this.windowSize = b.windowSize;
        this.headroomMultiplier = Math.max(1.0, b.headroomMultiplier);
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    // Getters...
    public double percentile() { return percentile; }
    public Duration minTimeout() { return minTimeout; }
    public Duration maxTimeout() { return maxTimeout; }
    public Duration coldStartTimeout() { return coldStartTimeout; }
    public int minSamples() { return minSamples; }
    public Duration windowSize() { return windowSize; }
    public double headroomMultiplier() { return headroomMultiplier; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private double percentile = 0.99;
        private Duration minTimeout = Duration.ofMillis(50);
        private Duration maxTimeout = Duration.ofSeconds(30);
        private Duration coldStartTimeout = Duration.ofSeconds(5);
        private int minSamples = 100;
        private Duration windowSize = Duration.ofSeconds(60);
        private double headroomMultiplier = 1.5;

        public Builder percentile(double v) { this.percentile = v; return this; }
        public Builder minTimeout(Duration v) { this.minTimeout = v; return this; }
        public Builder maxTimeout(Duration v) { this.maxTimeout = v; return this; }
        public Builder coldStartTimeout(Duration v) { this.coldStartTimeout = v; return this; }
        public Builder minSamples(int v) { this.minSamples = v; return this; }
        public Builder windowSize(Duration v) { this.windowSize = v; return this; }
        public Builder headroomMultiplier(double v) { this.headroomMultiplier = v; return this; }
        public AdaptiveTimeoutConfig build() { return new AdaptiveTimeoutConfig(this); }
    }
}
```

**Why `headroomMultiplier`?** Setting timeout at exactly P99 means ~1% of requests always time out under normal conditions. The multiplier (default 1.5x) provides headroom: P99 at 200ms → timeout at 300ms.

### 3.6 `SlidingWindowHistogram` (Internal)

```java
package io.deadline4j;

/**
 * Two-phase sliding window over HdrHistogram. Not public API.
 *
 * <p>Maintains two HdrHistogram instances, rotated at windowSize/2
 * intervals. Queries merge both, providing a rolling view covering
 * at least windowSize/2 of recent data without per-second bucket cost.
 *
 * <p>Thread-safe: recordValue is lock-free on the hot path (no rotation
 * needed). Rotation acquires a lock but happens at most twice per window.
 */
final class SlidingWindowHistogram {

    // Both references must be read/written under rotateLock OR via
    // the volatile snapshot pattern below. The hot path (recordValue)
    // reads the snapshot without locking; rotation publishes a new
    // snapshot under the lock.
    private volatile HistogramPair pair;
    private volatile long rotationDeadlineNanos;
    private final long halfWindowNanos;
    private final long highestTrackableValue;
    private final Object rotateLock = new Object();

    /** Immutable pair of references. Published atomically via volatile. */
    private static final class HistogramPair {
        final org.HdrHistogram.Histogram current;
        final org.HdrHistogram.Histogram previous;
        HistogramPair(org.HdrHistogram.Histogram current,
                      org.HdrHistogram.Histogram previous) {
            this.current = current;
            this.previous = previous;
        }
    }

    SlidingWindowHistogram(long windowNanos, long highestTrackableValue) {
        this.halfWindowNanos = windowNanos / 2;
        this.highestTrackableValue = highestTrackableValue;
        this.pair = new HistogramPair(
            new org.HdrHistogram.Histogram(highestTrackableValue, 2),
            new org.HdrHistogram.Histogram(highestTrackableValue, 2));
        this.rotationDeadlineNanos = System.nanoTime() + halfWindowNanos;
    }

    void recordValue(long value) {
        maybeRotate();
        // Read volatile once. HdrHistogram.recordValue is thread-safe
        // (uses internal CAS for concurrent recording).
        pair.current.recordValue(Math.min(value, highestTrackableValue));
    }

    long getValueAtPercentile(double percentile) {
        maybeRotate();
        HistogramPair snap = pair; // single volatile read
        org.HdrHistogram.Histogram merged = snap.current.copy();
        merged.add(snap.previous);
        return merged.getValueAtPercentile(percentile);
    }

    long totalCount() {
        HistogramPair snap = pair;
        return snap.current.getTotalCount() + snap.previous.getTotalCount();
    }

    private void maybeRotate() {
        if (System.nanoTime() < rotationDeadlineNanos) return;
        synchronized (rotateLock) {
            if (System.nanoTime() < rotationDeadlineNanos) return;
            HistogramPair old = pair;
            // Old previous becomes new current (after reset).
            // Old current becomes new previous (retains recent data).
            old.previous.reset();
            pair = new HistogramPair(old.previous, old.current);
            rotationDeadlineNanos = System.nanoTime() + halfWindowNanos;
        }
    }
}
```

### 3.7 `AdaptiveTimeoutRegistry`

```java
package io.deadline4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link AdaptiveTimeout} instances, keyed by service name.
 *
 * <p>Thread-safe. Typically one instance per application, managed by
 * Spring auto-configuration.
 */
public final class AdaptiveTimeoutRegistry {

    private final ConcurrentHashMap<String, AdaptiveTimeout> timeouts =
        new ConcurrentHashMap<>();
    private final DynamicConfigSource configSource;

    public AdaptiveTimeoutRegistry(DynamicConfigSource configSource) {
        this.configSource = configSource;
    }

    /** Get or create the adaptive timeout tracker for a service. */
    public AdaptiveTimeout forService(String name) {
        return timeouts.computeIfAbsent(name, n -> {
            try {
                return new AdaptiveTimeout(n, configSource.resolve(n));
            } catch (Exception e) {
                // Config source failure (e.g., Config Server unreachable).
                // Fall back to safe defaults rather than breaking the request.
                // Log at WARN level — this is a degraded state.
                return new AdaptiveTimeout(n, AdaptiveTimeoutConfig.builder().build());
            }
        });
    }

    /**
     * Reload configuration for a service. The next call to
     * forService() with a new name creates a fresh instance.
     * Existing instances retain their histogram data but pick up
     * new config on the next currentTimeout() call.
     */
    public void reloadConfig(String serviceName) {
        timeouts.computeIfPresent(serviceName, (name, existing) ->
            new AdaptiveTimeout(name, configSource.resolve(name)));
    }
}
```

### 3.8 `DeadlineCodec` — Pluggable Wire Format SPI

```java
package io.deadline4j;

/**
 * Serializes/deserializes deadlines to/from transport carriers.
 * Carrier-agnostic: works for HTTP headers, Kafka record headers,
 * gRPC metadata, AMQP message properties — anything with string
 * key-value pairs.
 *
 * <p>The default implementation uses X-Deadline-Remaining-Ms.
 * Teams with HLC infrastructure can provide an alternative.
 */
public interface DeadlineCodec {

    <C> void inject(Deadline deadline, C carrier, CarrierSetter<C> setter);

    <C> Deadline extract(C carrier, CarrierGetter<C> getter);

    static DeadlineCodec remainingMillis() {
        return new RemainingMillisCodec();
    }
}

@FunctionalInterface
public interface CarrierSetter<C> {
    void set(C carrier, String key, String value);
}

@FunctionalInterface
public interface CarrierGetter<C> {
    String get(C carrier, String key);
}
```

```java
package io.deadline4j;

/** Default codec: X-Deadline-Remaining-Ms header. */
final class RemainingMillisCodec implements DeadlineCodec {

    static final String HEADER_REMAINING_MS = "X-Deadline-Remaining-Ms";
    static final String HEADER_DEADLINE_ID = "X-Deadline-Id";

    @Override
    public <C> void inject(Deadline deadline, C carrier, CarrierSetter<C> setter) {
        setter.set(carrier, HEADER_REMAINING_MS,
            String.valueOf(deadline.remainingMillis()));
        deadline.id().ifPresent(id ->
            setter.set(carrier, HEADER_DEADLINE_ID, id));
    }

    @Override
    public <C> Deadline extract(C carrier, CarrierGetter<C> getter) {
        String raw = getter.get(carrier, HEADER_REMAINING_MS);
        if (raw == null) return null;
        try {
            long ms = Long.parseLong(raw);
            if (ms <= 0) return null;
            String id = getter.get(carrier, HEADER_DEADLINE_ID);
            return Deadline.fromRemainingMillis(ms, id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

### 3.9 `DeadlineTimer` — Pluggable Timer SPI

```java
package io.deadline4j;

import java.util.concurrent.*;

/**
 * Abstraction over timer scheduling for deadline expiration callbacks.
 *
 * <p>The default uses a single-thread ScheduledExecutorService.
 * For 100K+ concurrent deadlines, swap in Netty's HashedWheelTimer
 * (via deadline4j-timer-netty) or Agrona's DeadlineTimerWheel
 * (via deadline4j-timer-agrona).
 */
public interface DeadlineTimer {

    TimerHandle schedule(Runnable task, long delay, TimeUnit unit);

    void shutdown();

    interface TimerHandle {
        boolean cancel();
    }

    /** Default: ScheduledExecutorService with 1 daemon thread. */
    static DeadlineTimer defaultTimer() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "deadline4j-timer"); t.setDaemon(true); return t; });
        return new DeadlineTimer() {
            @Override
            public TimerHandle schedule(Runnable task, long delay, TimeUnit unit) {
                ScheduledFuture<?> future = executor.schedule(task, delay, unit);
                return () -> future.cancel(false);
            }
            @Override
            public void shutdown() { executor.shutdown(); }
        };
    }
}
```

### 3.10 `DynamicConfigSource` — Runtime Config SPI

```java
package io.deadline4j;

/**
 * Provides configuration that can change without redeployment.
 *
 * <p>The default reads from Spring's Environment (refreshable via
 * Spring Cloud Config, Consul, etc.). Can be backed by a feature
 * flag system (LaunchDarkly, Unleash).
 */
@FunctionalInterface
public interface DynamicConfigSource {

    /** Resolve current config for a service. Thread-safe, may be called concurrently. */
    AdaptiveTimeoutConfig resolve(String serviceName);
}
```

### 3.11 `ServiceNameResolver` — Generic Target-to-Name Mapping

```java
package io.deadline4j;

/**
 * Maps a transport-specific target to a logical service name for
 * timeout tracking and configuration lookup.
 *
 * @param <T> the target type — URI for HTTP, String for Kafka topics,
 *            MethodDescriptor for gRPC.
 */
@FunctionalInterface
public interface ServiceNameResolver<T> {
    String resolve(T target);

    /** Default for HTTP: uses the hostname as service name. */
    static ServiceNameResolver<java.net.URI> byHost() {
        return uri -> uri.getHost();
    }
}
```

### 3.12 `TimeoutBudget`

Tracks budget consumption across sequential calls. Used automatically by interceptors. Available to application code as an opt-in fine-tuning API.

```java
package io.deadline4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks time budget consumption across sequential downstream calls
 * within a single request.
 *
 * <p>Created automatically by the inbound filter and stored in the
 * DeadlineContext. Interceptors record consumption on each outbound
 * call. Application code can access it for programmatic degradation
 * decisions when YAML configuration isn't sufficient.
 *
 * <p>Not thread-safe — single request thread or reactive chain.
 * For parallel fan-out, use the underlying Deadline directly
 * (immutable, thread-safe).
 */
public final class TimeoutBudget {

    /** No-op budget: never expires, always affords, records nothing. */
    public static final TimeoutBudget NOOP = new TimeoutBudget(
        Deadline.after(Duration.ofDays(365)), true);

    private final Deadline deadline;
    private final List<Segment> segments;
    private final boolean noop;

    private TimeoutBudget(Deadline deadline, boolean noop) {
        this.deadline = deadline;
        this.segments = noop ? Collections.emptyList() : new ArrayList<>();
        this.noop = noop;
    }

    public static TimeoutBudget from(Deadline deadline) {
        return new TimeoutBudget(deadline, false);
    }

    public static TimeoutBudget of(Duration total) {
        return new TimeoutBudget(Deadline.after(total), false);
    }

    // --- Storage: one budget per request, stored alongside the Deadline ---

    private static final ThreadLocal<TimeoutBudget> CURRENT_BUDGET = new ThreadLocal<>();

    /**
     * Get the current budget, or NOOP if none is set.
     * This is the main entry point for application code AND interceptors.
     */
    public static TimeoutBudget current() {
        TimeoutBudget b = CURRENT_BUDGET.get();
        return b != null ? b : NOOP;
    }

    /**
     * Attach a budget to the current thread. Called by the inbound
     * filter (DeadlineFilter / DeadlineWebFilter) after creating the
     * Deadline. Returns a Scope for try-with-resources.
     */
    public static DeadlineContext.Scope attachBudget(TimeoutBudget budget) {
        TimeoutBudget previous = CURRENT_BUDGET.get();
        CURRENT_BUDGET.set(budget);
        return () -> {
            if (previous == null) {
                CURRENT_BUDGET.remove();
            } else {
                CURRENT_BUDGET.set(previous);
            }
        };
    }

    public Duration remaining() { return deadline.remaining(); }

    public boolean isExpired() { return deadline.isExpired(); }

    public boolean canAfford(Duration estimate) {
        return remaining().compareTo(estimate) >= 0;
    }

    public Duration allocate(Duration maxAllocation) {
        Duration r = remaining();
        return r.compareTo(maxAllocation) < 0 ? r : maxAllocation;
    }

    /** Record consumption. Called by interceptors automatically. */
    public void recordConsumption(String callName, Duration consumed) {
        if (!noop) {
            segments.add(new Segment(callName, consumed));
        }
    }

    public List<Segment> segments() {
        return Collections.unmodifiableList(segments);
    }

    public Deadline deadline() { return deadline; }

    public static final class Segment {
        private final String name;
        private final Duration consumed;

        public Segment(String name, Duration consumed) {
            this.name = name;
            this.consumed = consumed;
        }
        public String name() { return name; }
        public Duration consumed() { return consumed; }
    }
}
```

### 3.13 `DeadlineExceededException`

```java
package io.deadline4j;

/**
 * Thrown when a deadline has expired before or during a downstream call.
 * Extends RuntimeException — recoverable, services should catch for
 * fallback logic (or let the framework handle it).
 */
public class DeadlineExceededException extends RuntimeException {

    private final String serviceName;
    private final long budgetRemainingMs;

    public DeadlineExceededException(String message) {
        this(message, null, -1);
    }

    public DeadlineExceededException(String message, String serviceName,
                                      long budgetRemainingMs) {
        super(message);
        this.serviceName = serviceName;
        this.budgetRemainingMs = budgetRemainingMs;
    }

    public String serviceName() { return serviceName; }
    public long budgetRemainingMs() { return budgetRemainingMs; }
}
```

```java
package io.deadline4j;

/**
 * Thrown internally when an optional call is skipped due to insufficient
 * budget. Caught by the Feign fallback factory or RestTemplate interceptor
 * to return a default value. Never escapes to application code.
 */
public class OptionalCallSkippedException extends DeadlineExceededException {

    public OptionalCallSkippedException(String serviceName, long budgetRemainingMs) {
        super("Skipping optional call to " + serviceName
            + " — only " + budgetRemainingMs + "ms remaining",
            serviceName, budgetRemainingMs);
    }
}
```

---

## 4. Zero-Code Spring Integration

### 4.1 The gRPC Model Applied to Spring

In gRPC, the framework handles everything:

| Concern | gRPC mechanism | deadline4j equivalent |
|---|---|---|
| Extract incoming deadline | `ServerImpl.createContext()` | Servlet `Filter` / `WebFilter` |
| Attach to request context | `io.grpc.Context` | `DeadlineContext` via ThreadLocal / Reactor Context |
| Propagate to outgoing calls | `ClientCallImpl.effectiveDeadline()` | RestTemplate / WebClient / Feign interceptors |
| Enforce timeout | `ScheduledExecutorService` cancellation | Adaptive timeout applied as socket timeout |
| Cascade cancellation | `Context.CancellationListener` chain | WebFlux: reactive cancellation. WebMVC: interceptor boundary checks. |
| Degrade optional calls | Not supported in gRPC | Configuration-driven `priority: optional` |

**The application developer's code looks like this:**

```java
@RestController
public class OrderController {

    @Autowired private InventoryClient inventoryClient;
    @Autowired private PricingClient pricingClient;
    @Autowired private RecommendationClient recommendationClient;

    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable String id) {
        // No deadline code. No budget code. No timing code.
        // Everything is handled by interceptors and configuration.
        Inventory inv = inventoryClient.check(id);
        Pricing pricing = pricingClient.getPrice(id);
        List<Product> recs = recommendationClient.forProduct(id);
        return new OrderResponse(inv, pricing, recs);
    }
}
```

**What happens behind the scenes:**
1. `DeadlineFilter` extracts `X-Deadline-Remaining-Ms` from the incoming request, creates a `Deadline`, attaches it to `DeadlineContext`, and creates a `TimeoutBudget`.
2. When `inventoryClient.check(id)` executes, the Feign interceptor reads `DeadlineContext.current()`, computes `effectiveTimeout = min(adaptiveTimeout, remainingDeadline)`, sets the outgoing `X-Deadline-Remaining-Ms` header, applies the timeout to the HTTP client, and records latency + budget consumption on completion.
3. Same for `pricingClient.getPrice(id)`.
4. For `recommendationClient.forProduct(id)`: the interceptor sees this service is configured as `priority: optional` with `min-budget-required: 200ms`. If less than 200ms remains in the budget, the interceptor returns an empty fallback **without making the call**. The application code receives an empty list and doesn't know the call was skipped.

### 4.2 Servlet Filter (WebMVC)

```java
package io.deadline4j.spring.webmvc;

// BUILD STRATEGY for javax vs jakarta:
// Two modules: deadline4j-spring-webmvc-javax (Spring Boot 2.x)
//              deadline4j-spring-webmvc-jakarta (Spring Boot 3.x)
// Both share the same source (symlink or Gradle source set) with
// only the import line differing. The spring-boot-starter pulls
// the correct one via @ConditionalOnClass detection.
// Shown here with javax for brevity.
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

import io.deadline4j.*;

/**
 * Extracts deadline from incoming headers, attaches to DeadlineContext,
 * creates a TimeoutBudget. Mirrors gRPC ServerImpl.createContext().
 *
 * <p>Registered at highest filter order for maximum remaining-time accuracy.
 */
public class DeadlineFilter implements Filter {

    private final DeadlineCodec codec;
    private final Duration defaultDeadline;      // null = no default
    private final EnforcementMode enforcementMode;

    public DeadlineFilter(DeadlineCodec codec, Duration defaultDeadline,
                          EnforcementMode enforcementMode) {
        this.codec = codec;
        this.defaultDeadline = defaultDeadline;
        this.enforcementMode = enforcementMode;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        Deadline deadline = codec.extract(httpReq, HttpServletRequest::getHeader);

        if (deadline == null && defaultDeadline != null) {
            deadline = Deadline.after(defaultDeadline);
        }

        if (deadline != null) {
            TimeoutBudget budget = TimeoutBudget.from(deadline);
            try (DeadlineContext.Scope s1 = DeadlineContext.attach(deadline);
                 DeadlineContext.Scope s2 = TimeoutBudget.attachBudget(budget)) {
                chain.doFilter(request, response);
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
```

### 4.3 Feign Interceptor with Automatic Degradation

```java
package io.deadline4j.spring.openfeign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.deadline4j.*;

/**
 * Zero-code interceptor for Feign clients.
 *
 * <p>Automatically:
 * <ul>
 *   <li>Propagates deadline as X-Deadline-Remaining-Ms header</li>
 *   <li>Computes effective timeout = min(adaptive, remaining deadline)</li>
 *   <li>Records latency to the AdaptiveTimeout</li>
 *   <li>Records consumption to the TimeoutBudget</li>
 *   <li>For optional services: skips call and returns fallback if budget
 *       is insufficient (based on YAML config, not code)</li>
 * </ul>
 */
public class DeadlineFeignInterceptor implements RequestInterceptor {

    private final DeadlineCodec codec;
    private final AdaptiveTimeoutRegistry registry;
    private final ServiceNameResolver<RequestTemplate> nameResolver;
    private final ServiceConfigRegistry serviceConfigRegistry;

    @Override
    public void apply(RequestTemplate template) {
        Deadline deadline = DeadlineContext.capture();
        if (deadline == null) return;

        String serviceName = nameResolver.resolve(template);
        ServiceConfig serviceConfig = serviceConfigRegistry.forService(serviceName);

        // Check enforcement mode
        if (serviceConfig.enforcement() == EnforcementMode.DISABLED) return;
        boolean enforcing = serviceConfig.enforcement() == EnforcementMode.ENFORCE;

        long remainingMs = deadline.remainingMillis();

        // Automatic degradation for optional services
        if (serviceConfig.priority() == ServicePriority.OPTIONAL && enforcing) {
            Duration minBudget = serviceConfig.minBudgetRequired();
            if (minBudget != null && remainingMs < minBudget.toMillis()) {
                throw new OptionalCallSkippedException(serviceName, remainingMs);
                // Caught by DeadlineFeignFallbackFactory, returns empty response.
            }
        }

        // Propagate deadline header
        if (remainingMs > 0) {
            codec.inject(deadline, template, RequestTemplate::header);
        } else if (enforcing) {
            throw new DeadlineExceededException(
                "Deadline expired before calling " + serviceName,
                serviceName, 0);
        }

        // Effective timeout = min(adaptive, remaining)
        AdaptiveTimeout adaptive = registry.forService(serviceName);
        Duration timeout = enforcing
            ? adaptive.effectiveTimeout(deadline)
            : adaptive.currentTimeout();
        template.request().options(new feign.Request.Options(
            (int) timeout.toMillis(), (int) timeout.toMillis()));
    }
}
```

```java
package io.deadline4j.spring.openfeign;

import feign.FallbackFactory;
import io.deadline4j.DeadlineExceededException;

import java.lang.reflect.Proxy;

/**
 * Auto-registered fallback factory for Feign clients.
 *
 * <p>When an optional call is skipped (OptionalCallSkippedException)
 * or deadline exceeded, returns a proxy that returns sensible defaults:
 * null for objects, empty collections, Optional.empty(), 0 for numbers,
 * false for booleans.
 *
 * <p>Application code receives the default value and is unaware the
 * call was skipped.
 */
public class Deadline4jFallbackFactory<T> implements FallbackFactory<T> {

    private final Class<T> clientType;

    public Deadline4jFallbackFactory(Class<T> clientType) {
        this.clientType = clientType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T create(Throwable cause) {
        if (cause instanceof OptionalCallSkippedException
                || cause instanceof DeadlineExceededException) {
            return (T) Proxy.newProxyInstance(
                clientType.getClassLoader(),
                new Class[]{clientType},
                (proxy, method, args) -> DefaultReturnValues.forType(
                    method.getReturnType()));
        }
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        throw new RuntimeException(cause);
    }
}
```

```java
package io.deadline4j.spring.openfeign;

import java.util.*;

/**
 * Computes sensible default return values by type.
 * Used by fallback factory when optional calls are skipped.
 */
final class DefaultReturnValues {

    static Object forType(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == Optional.class) return Optional.empty();
        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        if (Set.class.isAssignableFrom(type)) return Collections.emptySet();
        if (Map.class.isAssignableFrom(type)) return Collections.emptyMap();
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0;
        return null;
    }
}
```

### 4.4 RestTemplate Interceptor

```java
package io.deadline4j.spring.webmvc;

import io.deadline4j.*;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.*;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Zero-code interceptor for RestTemplate.
 *
 * <p>Propagates deadline, applies adaptive timeout, records latency
 * and budget consumption automatically.
 *
 * <p>Per-request timeout is applied via a {@link TimeoutApplyingExecution}
 * wrapper that sets the timeout on the underlying HTTP client before
 * each request. This requires Apache HttpClient (which supports
 * per-request {@code RequestConfig} via {@code HttpContext}) or OkHttp.
 *
 * <p>{@code SimpleClientHttpRequestFactory} does NOT support per-request
 * timeouts thread-safely. Auto-configuration validates the factory at
 * startup and logs a warning if an unsupported factory is detected.
 */
public class DeadlineRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private final DeadlineCodec codec;
    private final AdaptiveTimeoutRegistry registry;
    private final ServiceNameResolver<URI> nameResolver;
    private final ServiceConfigRegistry serviceConfigRegistry;

    // Constructor...

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        Deadline deadline = DeadlineContext.capture();
        String serviceName = nameResolver.resolve(request.getURI());
        ServiceConfig config = serviceConfigRegistry.forService(serviceName);

        if (config.enforcement() == EnforcementMode.DISABLED || deadline == null) {
            // Still record latency in all modes for adaptive timeout warm-up.
            return recordingExecute(serviceName, execution, request, body);
        }

        boolean enforcing = config.enforcement() == EnforcementMode.ENFORCE;

        // Auto-degrade optional calls
        if (config.priority() == ServicePriority.OPTIONAL && enforcing) {
            Duration minBudget = config.minBudgetRequired();
            if (minBudget != null && deadline.remainingMillis() < minBudget.toMillis()) {
                return EmptyClientHttpResponse.forSkippedCall(serviceName);
            }
        }

        // Propagate header
        long remainingMs = deadline.remainingMillis();
        if (remainingMs <= 0 && enforcing) {
            throw new DeadlineExceededException(
                "Deadline expired before calling " + serviceName,
                serviceName, 0);
        }
        codec.inject(deadline, request.getHeaders(),
            (headers, key, value) -> headers.set(key, value));

        // Compute effective timeout
        AdaptiveTimeout adaptive = registry.forService(serviceName);
        Duration timeout = enforcing
            ? adaptive.effectiveTimeout(deadline)
            : adaptive.currentTimeout();

        // Apply per-request timeout via X-Deadline-Internal-Timeout-Ms header.
        // The DeadlineRequestFactory (registered by auto-config via
        // RestTemplateCustomizer) reads this header before each request
        // and applies it to the underlying Apache HttpClient RequestConfig:
        //
        //   RequestConfig requestConfig = RequestConfig.custom()
        //       .setSocketTimeout((int) timeoutMs)
        //       .setConnectTimeout((int) timeoutMs)
        //       .build();
        //   HttpContext context = HttpClientContext.create();
        //   context.setAttribute(HttpClientContext.REQUEST_CONFIG, requestConfig);
        //
        // This is the standard Apache HttpClient pattern for per-request
        // timeouts. The internal header is stripped before the request
        // goes on the wire — it never reaches the downstream service.
        request.getHeaders().set("X-Deadline-Internal-Timeout-Ms",
            String.valueOf(timeout.toMillis()));

        return recordingExecute(serviceName, execution, request, body);
    }

    private ClientHttpResponse recordingExecute(String serviceName,
            ClientHttpRequestExecution execution, HttpRequest request,
            byte[] body) throws IOException {
        AdaptiveTimeout adaptive = registry.forService(serviceName);
        long startNanos = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            adaptive.recordLatency(Duration.ofNanos(System.nanoTime() - startNanos));
            return response;
        } catch (IOException e) {
            adaptive.recordLatency(Duration.ofNanos(System.nanoTime() - startNanos));
            throw e;
        }
    }
}
```

### 4.5 WebFlux WebFilter + WebClient Filter

```java
package io.deadline4j.spring.webflux;

import io.deadline4j.*;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Bridges DeadlineContext (ThreadLocal) and Reactor's Context.
 * Lives in deadline4j-spring-webflux (has reactor-core dependency).
 */
public final class ReactorDeadlineBridge {

    static final String CONTEXT_KEY = "io.deadline4j.Deadline";

    private ReactorDeadlineBridge() {}

    public static Context withDeadline(Context ctx, Deadline deadline) {
        return ctx.put(CONTEXT_KEY, deadline);
    }

    public static Deadline fromContext(ContextView ctx) {
        return ctx.getOrDefault(CONTEXT_KEY, null);
    }
}
```

```java
package io.deadline4j.spring.webflux;

import io.deadline4j.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;

/**
 * Reactive equivalent of DeadlineFilter. Extracts deadline from
 * headers and places it in Reactor's Context (not ThreadLocal).
 */
public class DeadlineWebFilter implements WebFilter {

    private final DeadlineCodec codec;
    private final Duration defaultDeadline;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Deadline deadline = codec.extract(
            exchange.getRequest().getHeaders(),
            (headers, key) -> headers.getFirst(key));

        if (deadline == null && defaultDeadline != null) {
            deadline = Deadline.after(defaultDeadline);
        }

        if (deadline != null) {
            Deadline d = deadline;
            return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, d));
        }
        return chain.filter(exchange);
    }
}
```

```java
package io.deadline4j.spring.webflux;

import io.deadline4j.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

/**
 * Reactive WebClient interceptor. Propagates deadline, applies
 * adaptive timeout via Mono.timeout(), records latency. Zero code.
 *
 * <p>Cancellation cascades automatically: when the upstream deadline
 * expires, the Mono.timeout() fires, which cancels the in-flight
 * HTTP request via Reactor's Disposable mechanism. This mirrors
 * gRPC's CancellationListener cascade.
 */
public class DeadlineWebClientFilter implements ExchangeFilterFunction {

    private final DeadlineCodec codec;
    private final AdaptiveTimeoutRegistry registry;
    private final ServiceNameResolver<URI> nameResolver;
    private final ServiceConfigRegistry serviceConfigRegistry;

    @Override
    public Mono<ClientResponse> filter(ClientRequest request,
                                        ExchangeFunction next) {
        return Mono.deferContextual(ctx -> {
            Deadline deadline = ctx.getOrDefault(
                ReactorDeadlineBridge.CONTEXT_KEY, null);
            if (deadline == null) return next.exchange(request);

            String serviceName = nameResolver.resolve(request.url());
            ServiceConfig config = serviceConfigRegistry.forService(serviceName);

            if (config.enforcement() == EnforcementMode.DISABLED) {
                return next.exchange(request);
            }
            boolean enforcing = config.enforcement() == EnforcementMode.ENFORCE;

            // Auto-degrade optional calls
            if (config.priority() == ServicePriority.OPTIONAL && enforcing) {
                Duration minBudget = config.minBudgetRequired();
                if (minBudget != null
                        && deadline.remainingMillis() < minBudget.toMillis()) {
                    return Mono.empty(); // Triggers switchIfEmpty / defaultIfEmpty upstream
                }
            }

            // Propagate header
            ClientRequest.Builder builder = ClientRequest.from(request);
            codec.inject(deadline, builder,
                (b, key, value) -> b.header(key, value));

            // Adaptive timeout
            AdaptiveTimeout adaptive = registry.forService(serviceName);
            Duration timeout = enforcing
                ? adaptive.effectiveTimeout(deadline)
                : adaptive.currentTimeout();

            long startNanos = System.nanoTime();

            return next.exchange(builder.build())
                .timeout(timeout) // Automatic cancellation on expiry
                .doOnTerminate(() -> {
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                    adaptive.recordLatency(elapsed);
                });
        });
    }
}
```

---

## 5. Configuration-Driven Degradation

### 5.1 Service Configuration Model

```java
package io.deadline4j;

import java.time.Duration;

/**
 * Per-service deadline behavior configuration.
 */
public final class ServiceConfig {

    private final EnforcementMode enforcement; // observe | enforce | disabled
    private final ServicePriority priority;    // required | optional
    private final Duration minBudgetRequired;  // null = no minimum
    private final Duration maxDeadline;        // server-imposed ceiling

    // Builder, getters...
}

public enum EnforcementMode { OBSERVE, ENFORCE, DISABLED }
public enum ServicePriority { REQUIRED, OPTIONAL }
```

```java
package io.deadline4j;

/**
 * Registry for per-service configuration, backed by DynamicConfigSource.
 * Supports runtime reload without redeployment.
 */
public final class ServiceConfigRegistry {

    private final DynamicServiceConfigSource configSource;

    public ServiceConfig forService(String serviceName) {
        return configSource.resolve(serviceName);
    }
}

@FunctionalInterface
public interface DynamicServiceConfigSource {
    ServiceConfig resolve(String serviceName);
}
```

### 5.2 Full Configuration Example

```yaml
deadline4j:
  # Master switch. observe = track everything, enforce nothing.
  enforcement: enforce

  # Default deadline for requests arriving without one.
  default-deadline: 10s

  # Global adaptive timeout defaults.
  adaptive:
    percentile: 0.99
    min-timeout: 50ms
    max-timeout: 30s
    cold-start-timeout: 5s
    min-samples: 100
    window-size: 60s
    headroom-multiplier: 1.5

  # Automatic error-rate circuit breaker.
  # If >10% of requests to a service hit DeadlineExceeded within 60s,
  # fall back to cold-start timeout for that service.
  safety:
    circuit-breaker-threshold: 0.10
    circuit-breaker-window: 60s

  # Per-service overrides.
  services:
    inventory-service:
      priority: required              # Never skipped.
      max-deadline: 5s                # Server-imposed ceiling.
      adaptive:
        percentile: 0.999

    pricing-service:
      priority: required
      adaptive:
        max-timeout: 2s

    recommendation-service:
      priority: optional              # Skipped if budget insufficient.
      min-budget-required: 200ms      # Skip if less than 200ms remaining.
      enforcement: enforce
      adaptive:
        max-timeout: 500ms
        cold-start-timeout: 200ms

    legacy-inventory:
      enforcement: disabled           # Excluded entirely.

    payment-service:
      priority: required
      enforcement: observe            # Newly onboarded — observe only.
      adaptive:
        percentile: 0.999
        headroom-multiplier: 2.0
```

### 5.3 Rollback and Safety Mechanisms

| Mechanism | What it does |
|---|---|
| `enforcement: observe` | Global or per-service. Tracks metrics, propagates headers, but never enforces timeouts, never skips calls, never throws `DeadlineExceededException`. Safe to deploy to production. |
| `enforcement: disabled` | Per-service. Library is a complete passthrough for this service. |
| `DynamicConfigSource` SPI | Config changes take effect without redeployment. Backed by Spring Cloud Config, Consul, feature flags, etc. |
| Absolute safety bounds | `minTimeout` cannot go below 1ms. `maxTimeout` cannot exceed 5 minutes. Hardcoded in `AdaptiveTimeoutConfig`, not overridable. |
| Automatic circuit breaker | If deadline-exceeded rate for a service exceeds `circuit-breaker-threshold` within `circuit-breaker-window`, falls back to `coldStartTimeout` and emits `deadline4j.safety.circuit_open` metric. |
| `NOOP` budget | When no deadline is set, `TimeoutBudget.current()` returns a no-op that never expires, always affords, records nothing. Code written against the budget API works identically with or without deadline4j active. |

---

## 6. Observability

### 6.1 Micrometer Metrics

```java
package io.deadline4j.micrometer;

import io.micrometer.core.instrument.*;

/**
 * Metrics emitted automatically. No application code needed.
 *
 * deadline4j.call.duration           Timer
 *   Duration of each downstream call.
 *   Tags: service, outcome (success|timeout|error)
 *
 * deadline4j.adaptive.timeout.ms     Gauge
 *   Current computed adaptive timeout per service.
 *   Tags: service
 *
 * deadline4j.adaptive.percentile.ms  Gauge
 *   Raw percentile value (before headroom) per service.
 *   Tags: service
 *
 * deadline4j.budget.consumed.ratio   DistributionSummary
 *   Fraction of budget consumed when request completes. [0.0, 1.0+].
 *   Values > 1.0 indicate deadline exceeded.
 *
 * deadline4j.deadline.exceeded        Counter
 *   Requests that exceeded deadline.
 *   Tags: service, phase (before_call|during_call)
 *
 * deadline4j.call.skipped             Counter
 *   Optional calls skipped due to insufficient budget.
 *   Tags: service
 *
 * deadline4j.safety.circuit_open      Counter
 *   Circuit breaker activations due to high error rate.
 *   Tags: service
 *
 * deadline4j.remaining.at_call.ms     DistributionSummary
 *   Budget remaining when each downstream call starts.
 *   Tags: service
 */
public class Deadline4jMetrics { /* ... */ }
```

**Metrics by enforcement mode:**

| Metric | `observe` | `enforce` | `disabled` |
|---|---|---|---|
| `deadline4j.call.duration` | Emitted | Emitted | Not emitted |
| `deadline4j.adaptive.timeout.ms` | Emitted | Emitted | Not emitted |
| `deadline4j.budget.consumed.ratio` | Emitted | Emitted | Not emitted |
| `deadline4j.deadline.exceeded` | Emitted (hypothetical — what *would* have been exceeded) | Emitted (actual) | Not emitted |
| `deadline4j.call.skipped` | Not emitted (calls never skipped) | Emitted | Not emitted |
| `deadline4j.remaining.at_call.ms` | Emitted | Emitted | Not emitted |

In `observe` mode, all tracking metrics are emitted so you can validate behavior before flipping to `enforce`. The `deadline.exceeded` counter in observe mode records violations that *would* have occurred, tagged with `mode=observe` to distinguish from actual enforcement.

```
```

### 6.2 OpenTelemetry Integration

```java
package io.deadline4j.opentelemetry;

import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * Propagates deadline as OTel baggage and records span attributes.
 *
 * Span attributes (set automatically):
 *   deadline4j.remaining_ms       (long)   at span start
 *   deadline4j.budget_consumed    (double) at span end
 *   deadline4j.exceeded           (bool)   at span end
 *   deadline4j.call_skipped       (bool)   for optional calls
 *
 * Baggage keys:
 *   deadline-remaining-ms    propagated across process boundaries
 *   deadline-id              correlation ID
 */
public class DeadlineTextMapPropagator implements TextMapPropagator { /* ... */ }
```

---

## 7. Spring Boot Auto-Configuration

```java
package io.deadline4j.spring.autoconfigure;

import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(Deadline4jProperties.class)
public class Deadline4jAutoConfiguration {

    @Bean
    public AdaptiveTimeoutRegistry adaptiveTimeoutRegistry(Deadline4jProperties props) {
        return new AdaptiveTimeoutRegistry(serviceName -> {
            // Resolve from per-service config, fall back to global defaults
            Deadline4jProperties.AdaptiveDefaults config =
                props.getServices().containsKey(serviceName)
                    ? props.getServices().get(serviceName).getAdaptive()
                    : props.getAdaptive();
            return /* build AdaptiveTimeoutConfig from config */;
        });
    }

    @Bean
    public ServiceConfigRegistry serviceConfigRegistry(Deadline4jProperties props) {
        return new ServiceConfigRegistry(serviceName -> {
            // Resolve enforcement, priority, minBudgetRequired per service
        });
    }

    @Bean
    public DeadlineCodec deadlineCodec() {
        return DeadlineCodec.remainingMillis();
    }

    // --- WebMVC ---
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebMvcConfig {
        @Bean
        public DeadlineFilter deadlineFilter(DeadlineCodec codec,
                Deadline4jProperties props) {
            return new DeadlineFilter(codec, props.getDefaultDeadline(),
                props.getEnforcement());
        }

        @Bean
        public DeadlineRestTemplateInterceptor restTemplateInterceptor(
                DeadlineCodec codec, AdaptiveTimeoutRegistry registry,
                ServiceConfigRegistry serviceConfig) {
            return new DeadlineRestTemplateInterceptor(codec, registry,
                uri -> uri.getHost(), serviceConfig);
        }

        /**
         * Automatically registers the deadline interceptor on all
         * RestTemplate beans created via RestTemplateBuilder.
         * For manually-created RestTemplate beans, a BeanPostProcessor
         * adds the interceptor.
         */
        @Bean
        public RestTemplateCustomizer deadlineRestTemplateCustomizer(
                DeadlineRestTemplateInterceptor interceptor) {
            return restTemplate -> {
                List<ClientHttpRequestInterceptor> existing =
                    new ArrayList<>(restTemplate.getInterceptors());
                // Add at position 0 so deadline propagation wraps all other interceptors
                existing.add(0, interceptor);
                restTemplate.setInterceptors(existing);
            };
        }
    }

    // --- WebFlux ---
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class WebFluxConfig {
        @Bean
        public DeadlineWebFilter deadlineWebFilter(DeadlineCodec codec,
                Deadline4jProperties props) {
            return new DeadlineWebFilter(codec, props.getDefaultDeadline());
        }

        @Bean
        public DeadlineWebClientFilter webClientFilter(DeadlineCodec codec,
                AdaptiveTimeoutRegistry registry,
                ServiceConfigRegistry serviceConfig) {
            return new DeadlineWebClientFilter(codec, registry,
                ServiceNameResolver.byHost(), serviceConfig);
        }
    }

    // --- Feign ---
    @Configuration
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignConfig {
        @Bean
        public DeadlineFeignInterceptor feignInterceptor(DeadlineCodec codec,
                AdaptiveTimeoutRegistry registry,
                ServiceConfigRegistry serviceConfig) {
            return new DeadlineFeignInterceptor(codec, registry,
                template -> template.feignTarget().name(), // Spring Cloud Feign target name
                serviceConfig);
        }

        /**
         * Registers Deadline4jFallbackFactory for all Feign clients that
         * don't already have a fallback configured. Uses a BeanPostProcessor
         * to intercept FeignClientFactoryBean creation.
         *
         * NOTE: For this to work, Feign clients must have
         * fallback support enabled:
         *   spring.cloud.openfeign.circuitbreaker.enabled=true
         *
         * For clients that already declare their own fallback or
         * fallbackFactory, this processor does not interfere.
         */
        @Bean
        public static BeanPostProcessor deadline4jFeignFallbackRegistrar() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof org.springframework.cloud.openfeign
                            .FeignClientFactoryBean) {
                        var factory = (org.springframework.cloud.openfeign
                            .FeignClientFactoryBean) bean;
                        if (factory.getFallbackFactory() == void.class) {
                            factory.setFallbackFactory(Deadline4jFallbackFactory.class);
                        }
                    }
                    return bean;
                }
            };
        }
    }

    // --- Timer ---
    @Bean
    @ConditionalOnMissingBean(DeadlineTimer.class)
    public DeadlineTimer deadlineTimer() {
        return DeadlineTimer.defaultTimer();
    }

    @Configuration
    @ConditionalOnClass(name = "io.netty.util.HashedWheelTimer")
    @ConditionalOnProperty(prefix = "deadline4j", name = "timer", havingValue = "netty")
    static class NettyTimerConfig {
        @Bean
        public DeadlineTimer nettyTimer() {
            // Adapter in deadline4j-timer-netty module
            return /* NettyHashedWheelTimerAdapter */;
        }
    }
}
```

---

## 8. Testing Support

```java
package io.deadline4j.test;

import io.deadline4j.*;
import java.time.Duration;

/**
 * Utilities for unit testing code that uses deadline4j.
 * No Spring context required.
 */
public final class DeadlineTestSupport {

    /** Run a block with a specific remaining duration. */
    public static void withDeadline(Duration remaining, Runnable block) {
        try (DeadlineContext.Scope ignored =
                 DeadlineContext.attach(Deadline.after(remaining))) {
            block.run();
        }
    }

    /** Run with an already-expired deadline. */
    public static void withExpiredDeadline(Runnable block) {
        Deadline expired = Deadline.fromRemainingMillis(0);
        try (DeadlineContext.Scope ignored = DeadlineContext.attach(expired)) {
            block.run();
        }
    }

    /** Run with no deadline (clean state). */
    public static void withoutDeadline(Runnable block) {
        try (DeadlineContext.Scope ignored = DeadlineContext.clear()) {
            block.run();
        }
    }

    /**
     * Assert that a block completes within its budget.
     * Useful for integration tests.
     */
    public static void assertWithinBudget(Duration budget, Runnable block) {
        long start = System.nanoTime();
        withDeadline(budget, block);
        long elapsed = System.nanoTime() - start;
        if (elapsed > budget.toNanos()) {
            throw new AssertionError("Block exceeded budget: took "
                + Duration.ofNanos(elapsed).toMillis() + "ms, budget was "
                + budget.toMillis() + "ms");
        }
    }
}
```

---

## 9. Edge Cases and Failure Modes

### 9.1 Clock Skew
Mitigated by transmitting remaining duration, not absolute time. Error is limited to network transit time (<1ms LAN, ~50-100ms cross-region). Error is conservative — receiver gets slightly more time than intended, not less.

### 9.2 Cold Start Stampede
First `minSamples` requests use `coldStartTimeout` (default 5s). Intentionally generous — aggressive timeouts during cold start cascade with circuit breakers.

### 9.3 No Deadline Set
If no incoming header and `defaultDeadline` is null, the library is a passthrough. No deadline context, no enforcement, no degradation. Interceptors still record latency for adaptive timeout warm-up.

### 9.4 Incoming Deadline vs Server-Imposed Max-Deadline
When both an incoming `X-Deadline-Remaining-Ms` header and a per-service `max-deadline` config are present, the effective deadline is `min(incoming, max-deadline)`. The more restrictive value always wins. This mirrors gRPC's `effectiveDeadline = min(explicit, service-default, inherited)`. Applied in the inbound filter before attaching to `DeadlineContext`.

### 9.5 Negative or Zero Remaining Time at Parse
Filter ignores the header and does not set a deadline. Request proceeds without enforcement. Alternative (configurable): reject immediately with 504.

### 9.5 Parallel Fan-Out
`TimeoutBudget` is not thread-safe. For parallel calls, each branch uses the underlying `Deadline` directly (immutable, thread-safe) via `AdaptiveTimeout.effectiveTimeout(deadline)`. Budget segment tracking only applies to sequential chains.

### 9.6 RestTemplate Per-Request Timeout Limitation
`SimpleClientHttpRequestFactory` does not support per-request timeouts in a thread-safe manner. Auto-configuration detects this at startup, logs a warning, and falls back to the factory's global timeout. Apache HttpClient's `RequestConfig` via `HttpContext` is the recommended factory for deadline4j.

### 9.7 Mid-Call Cancellation
**WebFlux:** `Mono.timeout()` + reactive cancellation naturally propagates. When deadline expires, the in-flight HTTP request is cancelled via `Disposable.dispose()`. Mirrors gRPC's `CancellationListener` cascade.

**WebMVC:** HTTP/1.1 has no transport-level cancellation. Enforcement happens at interceptor boundaries (before starting a call, after receiving a response). A slow call will complete even if the deadline has expired; the interceptor detects this after the fact and records the violation. Thread interruption is available but risky — not all I/O libraries handle it cleanly.

### 9.8 Bad Configuration Rollback
1. Deploy with `enforcement: observe`. Metrics flow, nothing enforces.
2. Review `deadline4j.adaptive.timeout.ms` and `deadline4j.call.duration` metrics.
3. Flip to `enforcement: enforce` per-service via dynamic config.
4. If error rate spikes: automatic circuit breaker falls back to `coldStartTimeout`. Or flip back to `observe` via config — no redeploy.

---

## 10. Escape Hatch: Programmatic Budget API

For the 10% of cases where YAML config isn't sufficient (complex business logic, conditional degradation based on request content):

```java
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable String id) {
    // All downstream calls are still automatic (interceptors handle
    // propagation, enforcement, budget tracking).
    Inventory inv = inventoryClient.check(id);
    Pricing pricing = pricingClient.getPrice(id);

    // Programmatic check for complex degradation logic
    TimeoutBudget budget = TimeoutBudget.current();
    List<Product> recs;
    if (budget.canAfford(Duration.ofMillis(200)) && inv.isHighValue()) {
        recs = recommendationClient.forProduct(id);
    } else {
        recs = cachedRecommendations.forCategory(inv.category());
    }

    return new OrderResponse(inv, pricing, recs);
}
```

This is opt-in. The vast majority of services never import `TimeoutBudget`.

---

## 11. Build Coordinates

```xml
<!-- BOM -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.deadline4j</groupId>
      <artifactId>deadline4j-bom</artifactId>
      <version>${deadline4j.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- Typical Spring Boot application: one dependency. -->
<dependency>
  <groupId>io.deadline4j</groupId>
  <artifactId>deadline4j-spring-boot-starter</artifactId>
</dependency>

<!-- Optional: observability -->
<dependency>
  <groupId>io.deadline4j</groupId>
  <artifactId>deadline4j-micrometer</artifactId>
</dependency>
<dependency>
  <groupId>io.deadline4j</groupId>
  <artifactId>deadline4j-opentelemetry</artifactId>
</dependency>

<!-- Optional: high-scale timer -->
<dependency>
  <groupId>io.deadline4j</groupId>
  <artifactId>deadline4j-timer-netty</artifactId>
</dependency>
```
