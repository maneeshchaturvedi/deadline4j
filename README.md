# deadline4j

**Zero-code deadline propagation, adaptive timeouts, and timeout budgets for Spring applications.**

*Java 11+ &middot; Spring Boot 3.x &middot; Micrometer &middot; OpenTelemetry*

---

## Problem

In Spring microservices, timeout management is fragmented. RestTemplate, WebClient, and Feign each configure timeouts independently. No mechanism propagates a caller's deadline downstream. No library tracks how much of a request's time budget has been consumed across sequential calls. And fixed timeouts bear no relationship to actual service behavior.

gRPC solves this natively: `withDeadlineAfter(5, SECONDS)` at the edge propagates through every hop, is enforced automatically, cascades cancellation on expiry, and requires zero application code. Spring has no equivalent.

**deadline4j brings gRPC's zero-code deadline model to the Spring HTTP ecosystem.**

## Quick Start

Add one dependency:

```xml
<dependency>
    <groupId>io.deadline4j</groupId>
    <artifactId>deadline4j-spring-boot-starter</artifactId>
</dependency>
```

Add configuration:

```yaml
deadline4j:
  enforcement: observe    # Safe default: track everything, enforce nothing
  default-deadline: 10s   # Requests without a deadline header get 10s
```

That's it. Your application now:
- Extracts `X-Deadline-Remaining-Ms` from incoming requests
- Propagates deadlines to all outbound RestTemplate, WebClient, and Feign calls
- Tracks adaptive timeouts based on observed latency distributions
- Emits metrics via Micrometer (if on classpath)

When ready, flip to `enforce` per-service at your own pace.

## How It Works

```
Incoming request                     Outbound call
─────────────────                    ─────────────────
X-Deadline-Remaining-Ms: 5000       X-Deadline-Remaining-Ms: 3200
         │                                    ▲
         ▼                                    │
   DeadlineFilter                    RestTemplate/WebClient/Feign
   extracts deadline                 interceptor propagates deadline,
   attaches to context               applies adaptive timeout,
   creates TimeoutBudget             records latency + budget
```

1. **Inbound filter** extracts the deadline header, anchors it to the local monotonic clock, and attaches it to the request context.
2. **Outbound interceptors** read the deadline from context, compute `effectiveTimeout = min(adaptiveTimeout, remainingDeadline)`, propagate the header, and record latency.
3. **Optional services** are automatically skipped when budget is insufficient — application code receives empty defaults and is unaware the call was skipped.

## Design Principles

1. **Zero code at call sites.** Propagation, enforcement, and degradation happen in interceptors and filters.
2. **Configuration-driven degradation.** Required vs optional, enforcement mode, budget thresholds — all YAML.
3. **Safe by default.** Ships in `observe` mode. Tracks metrics but enforces nothing until explicitly opted in.
4. **Incremental adoption.** Deploy everywhere, enable enforcement per-service, per-environment.
5. **Framework-agnostic core.** The core module has zero framework dependencies.

## Modules

```
deadline4j-bom                          Maven BOM for version alignment
deadline4j-core                         Zero-dependency core (Java 11+)
deadline4j-spring-boot-starter          Auto-configuration for Spring Boot
deadline4j-spring-webmvc-jakarta        Servlet filter + RestTemplate interceptor (Boot 3.x)
deadline4j-spring-webflux               WebFilter + WebClient filter + Reactor bridge
deadline4j-spring-cloud-openfeign       Feign interceptor + fallback factory
deadline4j-micrometer                   Metrics via Micrometer
deadline4j-opentelemetry                OTel span attributes + baggage propagation
deadline4j-timer-netty                  HashedWheelTimer adapter (high-scale)
deadline4j-timer-agrona                 DeadlineTimerWheel adapter (ultra-low-latency)
```

### Dependency Graph

```
deadline4j-core  (Java 11 + HdrHistogram)
       |
       +-- deadline4j-spring-webmvc-jakarta  (+ spring-webmvc, jakarta.servlet)
       +-- deadline4j-spring-webflux         (+ spring-webflux, reactor-core)
       +-- deadline4j-spring-cloud-openfeign (+ feign-core)
       +-- deadline4j-micrometer             (+ micrometer-core)
       +-- deadline4j-opentelemetry          (+ opentelemetry-api)
       +-- deadline4j-timer-netty            (+ netty-common)
       +-- deadline4j-timer-agrona           (+ agrona)
              |
              +-- deadline4j-spring-boot-starter  (conditional auto-config)
```

## Configuration Reference

```yaml
deadline4j:
  # Master switch: observe | enforce
  enforcement: enforce

  # Default deadline for requests arriving without a header
  default-deadline: 10s

  # Server-imposed maximum deadline ceiling
  server-max-deadline: 30s

  # Global adaptive timeout defaults
  adaptive:
    percentile: 0.99           # P99 of observed latencies
    min-timeout: 50ms          # Floor
    max-timeout: 30s           # Ceiling
    cold-start-timeout: 5s     # Used before enough samples collected
    min-samples: 100           # Samples needed before switching to adaptive
    window-size: 60s           # Sliding window duration
    headroom-multiplier: 1.5   # Headroom above the percentile value

  # Per-service overrides
  services:
    inventory-service:
      priority: required
      max-deadline: 5s
      adaptive:
        percentile: 0.999

    recommendation-service:
      priority: optional
      min-budget-required: 200ms
      enforcement: enforce
      adaptive:
        max-timeout: 500ms

    legacy-service:
      enforcement: disabled
```

### Enforcement Modes

| Mode | Behavior |
|------|----------|
| `observe` | Tracks metrics, propagates headers, never enforces. Safe for initial rollout. |
| `enforce` | Full enforcement: timeouts applied, optional calls skipped, exceptions thrown. |
| `disabled` | Complete passthrough. Library is invisible for this service. |

### Service Priority

| Priority | Behavior in `enforce` mode |
|----------|---------------------------|
| `required` | Always called. `DeadlineExceededException` thrown if deadline expired before call. |
| `optional` | Skipped if remaining budget < `min-budget-required`. Application receives empty defaults. |

## Wire Protocol

```
X-Deadline-Remaining-Ms: 1450
X-Deadline-Id: txn-abc-123
```

Remaining duration (not absolute timestamp) is transmitted to avoid clock skew issues between hosts. The receiving service anchors to its local monotonic clock on parse.

## Programmatic API (Escape Hatch)

For the 10% of cases where YAML isn't enough:

```java
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable String id) {
    // Downstream calls are still automatic
    Inventory inv = inventoryClient.check(id);
    Pricing pricing = pricingClient.getPrice(id);

    // Programmatic budget check for complex degradation logic
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

## Observability

### Micrometer Metrics

| Metric | Type | Tags |
|--------|------|------|
| `deadline4j.call.duration` | Timer | service, outcome |
| `deadline4j.adaptive.timeout.ms` | Gauge | service |
| `deadline4j.adaptive.percentile.ms` | Gauge | service |
| `deadline4j.budget.consumed.ratio` | DistributionSummary | - |
| `deadline4j.deadline.exceeded` | Counter | service, phase, mode |
| `deadline4j.call.skipped` | Counter | service |
| `deadline4j.remaining.at_call.ms` | DistributionSummary | service |
| `deadline4j.safety.circuit_open` | Counter | service |

### OpenTelemetry

Span attributes set automatically:
- `deadline4j.remaining_ms` (long) at span start
- `deadline4j.budget_consumed` (double) at span end
- `deadline4j.exceeded` (bool) at span end
- `deadline4j.call_skipped` (bool) for optional calls

Baggage propagation via `deadline-remaining-ms` and `deadline-id` keys.

## Safe Rollout

1. Deploy with `enforcement: observe`. Metrics flow, nothing enforces.
2. Review `deadline4j.adaptive.timeout.ms` and `deadline4j.call.duration` in your dashboards.
3. Flip to `enforcement: enforce` per-service via dynamic config.
4. If error rate spikes: flip back to `observe` — no redeploy needed.

## What This Is Not

deadline4j brings gRPC's **propagation model** and **adaptive timeout model** to Spring. It does **not** bring gRPC's cancellation model to WebMVC. In blocking servlet containers, enforcement happens at interceptor boundaries (before starting a call, after receiving a response). A slow downstream call will complete even if the deadline has expired.

For true mid-call cancellation, use the WebFlux integration where `Mono.timeout()` actively cancels in-flight HTTP requests via Reactor's `Disposable` mechanism.

## Building

```bash
mvn clean verify
```

Requires Java 17+ for Spring Boot 3.x modules. The core module targets Java 11.

## License

TBD
