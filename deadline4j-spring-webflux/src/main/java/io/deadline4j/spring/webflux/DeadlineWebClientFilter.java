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

    public DeadlineWebClientFilter(DeadlineCodec codec,
                                    AdaptiveTimeoutRegistry registry,
                                    ServiceNameResolver<URI> nameResolver,
                                    ServiceConfigRegistry serviceConfigRegistry) {
        this.codec = codec;
        this.registry = registry;
        this.nameResolver = nameResolver;
        this.serviceConfigRegistry = serviceConfigRegistry;
    }

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
                    return Mono.empty();
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
                .timeout(timeout)
                .doOnTerminate(() -> {
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                    adaptive.recordLatency(elapsed);
                });
        });
    }
}
