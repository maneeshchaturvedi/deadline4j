package io.deadline4j.spring.openfeign;

import feign.RequestTemplate;
import io.deadline4j.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineFeignInterceptorTest {

    private DeadlineContext.Scope scope;

    @AfterEach
    void tearDown() {
        if (scope != null) {
            scope.close();
            scope = null;
        }
    }

    private DeadlineFeignInterceptor createInterceptor(ServiceConfig serviceConfig) {
        DeadlineCodec codec = DeadlineCodec.remainingMillis();
        AdaptiveTimeoutRegistry registry = new AdaptiveTimeoutRegistry(
            name -> AdaptiveTimeoutConfig.builder().build()
        );
        ServiceNameResolver<RequestTemplate> nameResolver = t -> "test-service";
        ServiceConfigRegistry serviceConfigRegistry = new ServiceConfigRegistry(
            name -> serviceConfig
        );
        return new DeadlineFeignInterceptor(codec, registry, nameResolver, serviceConfigRegistry);
    }

    @Test
    void propagatesDeadlineHeader() {
        scope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));

        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.ENFORCE)
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> values = template.headers().get("X-Deadline-Remaining-Ms");
        assertNotNull(values, "X-Deadline-Remaining-Ms header should be set");
        assertFalse(values.isEmpty());
        long remainingMs = Long.parseLong(values.iterator().next());
        assertTrue(remainingMs > 0 && remainingMs <= 5000,
            "Remaining ms should be between 0 and 5000, was " + remainingMs);
    }

    @Test
    void noDeadline_noOp() {
        // No deadline attached
        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.ENFORCE)
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertFalse(template.headers().containsKey("X-Deadline-Remaining-Ms"));
        assertFalse(template.headers().containsKey("X-Deadline-Internal-Timeout-Ms"));
    }

    @Test
    void disabledMode_noOp() {
        scope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));

        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.DISABLED)
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertFalse(template.headers().containsKey("X-Deadline-Remaining-Ms"));
        assertFalse(template.headers().containsKey("X-Deadline-Internal-Timeout-Ms"));
    }

    @Test
    void optionalCallSkipped() {
        // Attach a deadline with very little time remaining
        scope = DeadlineContext.attach(Deadline.after(Duration.ofMillis(100)));

        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.ENFORCE)
            .priority(ServicePriority.OPTIONAL)
            .minBudgetRequired(Duration.ofSeconds(10))
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        assertThrows(OptionalCallSkippedException.class, () -> interceptor.apply(template));
    }

    @Test
    void expiredDeadline_enforce_throws() {
        // Attach an already-expired deadline
        scope = DeadlineContext.attach(Deadline.after(Duration.ZERO));

        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.ENFORCE)
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        assertThrows(DeadlineExceededException.class, () -> interceptor.apply(template));
    }

    @Test
    void observeMode_propagatesButNoThrow() {
        // Attach an already-expired deadline
        scope = DeadlineContext.attach(Deadline.after(Duration.ZERO));

        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.OBSERVE)
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        // Should NOT throw even though deadline is expired
        assertDoesNotThrow(() -> interceptor.apply(template));

        // Header should NOT be set since remainingMs == 0
        assertFalse(template.headers().containsKey("X-Deadline-Remaining-Ms"),
            "Should not set header when remaining is 0");
    }

    @Test
    void setsInternalTimeoutHeader() {
        scope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));

        ServiceConfig config = ServiceConfig.builder()
            .enforcement(EnforcementMode.ENFORCE)
            .build();
        DeadlineFeignInterceptor interceptor = createInterceptor(config);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> values = template.headers().get("X-Deadline-Internal-Timeout-Ms");
        assertNotNull(values, "X-Deadline-Internal-Timeout-Ms header should be set");
        assertFalse(values.isEmpty());
        long timeoutMs = Long.parseLong(values.iterator().next());
        assertTrue(timeoutMs > 0, "Timeout should be positive, was " + timeoutMs);
    }
}
