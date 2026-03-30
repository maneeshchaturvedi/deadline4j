package io.deadline4j.spring.webflux;

import io.deadline4j.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineWebClientFilterTest {

    private static final URI TEST_URI = URI.create("http://test-service/api");

    private DeadlineCodec codec;
    private AdaptiveTimeoutRegistry registry;
    private ServiceNameResolver<URI> nameResolver;

    @BeforeEach
    void setUp() {
        codec = DeadlineCodec.remainingMillis();
        registry = new AdaptiveTimeoutRegistry(
                name -> AdaptiveTimeoutConfig.builder().build());
        nameResolver = ServiceNameResolver.byHost();
    }

    private DeadlineWebClientFilter createFilter(ServiceConfig config) {
        ServiceConfigRegistry scr = new ServiceConfigRegistry(name -> config);
        return new DeadlineWebClientFilter(codec, registry, nameResolver, scr);
    }

    private ClientRequest testRequest() {
        return ClientRequest.create(HttpMethod.GET, TEST_URI).build();
    }

    /**
     * Returns an ExchangeFunction that captures the request it receives
     * and returns a mock empty response.
     */
    private static ExchangeFunction capturingExchange(AtomicReference<ClientRequest> captured) {
        return request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        };
    }

    private static ExchangeFunction immediateExchange() {
        return request ->
                Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
    }

    // -------- Tests --------

    @Test
    void propagatesDeadlineHeader() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();
        DeadlineWebClientFilter filter = createFilter(config);
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));

        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        Mono<ClientResponse> result = filter.filter(testRequest(), capturingExchange(captured))
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, deadline));

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst("X-Deadline-Remaining-Ms")).isNotNull();
        long remainingMs = Long.parseLong(
                captured.get().headers().getFirst("X-Deadline-Remaining-Ms"));
        // Should be close to 10000ms (within a few hundred ms of creating the deadline)
        assertThat(remainingMs).isBetween(9000L, 10100L);
    }

    @Test
    void noDeadline_passthrough() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();
        DeadlineWebClientFilter filter = createFilter(config);

        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        // No deadline in context
        Mono<ClientResponse> result = filter.filter(testRequest(), capturingExchange(captured));

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        // The original request should be passed through unchanged (no header added)
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst("X-Deadline-Remaining-Ms")).isNull();
    }

    @Test
    void disabledMode_passthrough() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.DISABLED)
                .build();
        DeadlineWebClientFilter filter = createFilter(config);
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));

        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        Mono<ClientResponse> result = filter.filter(testRequest(), capturingExchange(captured))
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, deadline));

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        // Should pass through with original request (no header injection)
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst("X-Deadline-Remaining-Ms")).isNull();
    }

    @Test
    void optionalCallSkipped_monoEmpty() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .priority(ServicePriority.OPTIONAL)
                .minBudgetRequired(Duration.ofSeconds(10))
                .build();
        DeadlineWebClientFilter filter = createFilter(config);

        // Deadline with only 100ms remaining — not enough for the 10s min budget
        Deadline deadline = Deadline.after(Duration.ofMillis(100));

        Mono<ClientResponse> result = filter.filter(testRequest(), immediateExchange())
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, deadline));

        StepVerifier.create(result)
                .verifyComplete(); // Mono.empty() — no items emitted
    }

    @Test
    void recordsLatency() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();
        DeadlineWebClientFilter filter = createFilter(config);
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));

        AdaptiveTimeout adaptive = registry.forService("test-service");
        long countBefore = adaptive.sampleCount();

        Mono<ClientResponse> result = filter.filter(testRequest(), immediateExchange())
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, deadline));

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        assertThat(adaptive.sampleCount()).isEqualTo(countBefore + 1);
    }

    @Test
    void observeMode_propagatesButNoThrow() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.OBSERVE)
                .build();
        DeadlineWebClientFilter filter = createFilter(config);

        // Nearly expired deadline — only 5ms left
        Deadline deadline = Deadline.after(Duration.ofMillis(5));

        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        // Use a slightly delayed exchange to simulate real work but still within cold-start timeout
        ExchangeFunction exchange = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        };

        Mono<ClientResponse> result = filter.filter(testRequest(), exchange)
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, deadline));

        // In OBSERVE mode, the adaptive timeout (cold-start = 5s) is used, not
        // effectiveTimeout which would be ~5ms. So no timeout exception here.
        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        // Header should still be propagated
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst("X-Deadline-Remaining-Ms")).isNotNull();
    }

    @Test
    void appliesMonoTimeout() {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(EnforcementMode.ENFORCE)
                .build();
        DeadlineWebClientFilter filter = createFilter(config);

        // Deadline with 200ms remaining
        Deadline deadline = Deadline.after(Duration.ofMillis(200));

        // Exchange that takes much longer than the deadline
        ExchangeFunction slowExchange = request ->
                Mono.delay(Duration.ofSeconds(10))
                        .map(tick -> ClientResponse.create(org.springframework.http.HttpStatus.OK).build());

        Mono<ClientResponse> result = filter.filter(testRequest(), slowExchange)
                .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, deadline));

        StepVerifier.create(result)
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }
}
