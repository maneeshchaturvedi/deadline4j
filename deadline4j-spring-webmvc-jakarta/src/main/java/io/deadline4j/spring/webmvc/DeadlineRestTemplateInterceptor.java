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
 * <p>Per-request timeout is applied via a {@code X-Deadline-Internal-Timeout-Ms}
 * header that the request factory reads before each request. The header is
 * stripped before going on the wire.
 */
public class DeadlineRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private final DeadlineCodec codec;
    private final AdaptiveTimeoutRegistry registry;
    private final ServiceNameResolver<URI> nameResolver;
    private final ServiceConfigRegistry serviceConfigRegistry;

    public DeadlineRestTemplateInterceptor(DeadlineCodec codec,
            AdaptiveTimeoutRegistry registry,
            ServiceNameResolver<URI> nameResolver,
            ServiceConfigRegistry serviceConfigRegistry) {
        this.codec = codec;
        this.registry = registry;
        this.nameResolver = nameResolver;
        this.serviceConfigRegistry = serviceConfigRegistry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        Deadline deadline = DeadlineContext.capture();
        String serviceName = nameResolver.resolve(request.getURI());
        ServiceConfig config = serviceConfigRegistry.forService(serviceName);

        if (config.enforcement() == EnforcementMode.DISABLED || deadline == null) {
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

        // Set internal timeout header (stripped by request factory before going on wire)
        request.getHeaders().set("X-Deadline-Internal-Timeout-Ms",
            String.valueOf(timeout.toMillis()));

        return recordingExecute(serviceName, execution, request, body);
    }

    private ClientHttpResponse recordingExecute(String serviceName,
            ClientHttpRequestExecution execution, HttpRequest request,
            byte[] body) throws IOException {
        AdaptiveTimeout adaptive = registry.forService(serviceName);
        TimeoutBudget budget = TimeoutBudget.current();
        long startNanos = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            adaptive.recordLatency(elapsed);
            budget.recordConsumption(serviceName, elapsed);
            return response;
        } catch (IOException e) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            adaptive.recordLatency(elapsed);
            budget.recordConsumption(serviceName, elapsed);
            throw e;
        }
    }
}
