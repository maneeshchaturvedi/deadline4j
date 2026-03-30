package io.deadline4j.spring.openfeign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.deadline4j.*;

import java.time.Duration;

/**
 * Zero-code interceptor for Feign clients.
 *
 * <p>Automatically:
 * <ul>
 *   <li>Propagates deadline as {@code X-Deadline-Remaining-Ms} header</li>
 *   <li>Computes effective timeout = min(adaptive, remaining deadline)</li>
 *   <li>For optional services: skips call if budget is insufficient</li>
 * </ul>
 */
public class DeadlineFeignInterceptor implements RequestInterceptor {

    private final DeadlineCodec codec;
    private final AdaptiveTimeoutRegistry registry;
    private final ServiceNameResolver<RequestTemplate> nameResolver;
    private final ServiceConfigRegistry serviceConfigRegistry;

    public DeadlineFeignInterceptor(DeadlineCodec codec,
                                     AdaptiveTimeoutRegistry registry,
                                     ServiceNameResolver<RequestTemplate> nameResolver,
                                     ServiceConfigRegistry serviceConfigRegistry) {
        this.codec = codec;
        this.registry = registry;
        this.nameResolver = nameResolver;
        this.serviceConfigRegistry = serviceConfigRegistry;
    }

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
            }
        }

        // Propagate deadline header
        if (remainingMs > 0) {
            codec.inject(deadline, template, (t, k, v) -> t.header(k, v));
        } else if (enforcing) {
            throw new DeadlineExceededException(
                "Deadline expired before calling " + serviceName,
                serviceName, 0);
        }

        // Compute effective timeout and set internal header
        AdaptiveTimeout adaptive = registry.forService(serviceName);
        Duration timeout = enforcing
            ? adaptive.effectiveTimeout(deadline)
            : adaptive.currentTimeout();
        template.header("X-Deadline-Internal-Timeout-Ms",
            String.valueOf(timeout.toMillis()));
    }
}
