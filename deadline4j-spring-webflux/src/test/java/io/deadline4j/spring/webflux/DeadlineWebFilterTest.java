package io.deadline4j.spring.webflux;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineCodec;
import io.deadline4j.ServerDeadlineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadlineWebFilterTest {

    @Mock ServerWebExchange exchange;
    @Mock ServerHttpRequest request;

    private final DeadlineCodec codec = DeadlineCodec.remainingMillis();

    private WebFilterChain capturingChain(AtomicReference<Deadline> captured) {
        return ex -> Mono.deferContextual(ctx -> {
            captured.set(ReactorDeadlineBridge.fromContext(ctx));
            return Mono.empty();
        });
    }

    private void setupHeaders(HttpHeaders headers) {
        when(exchange.getRequest()).thenReturn(request);
        when(request.getHeaders()).thenReturn(headers);
    }

    @Test
    void extractsDeadlineFromHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "5000");
        setupHeaders(headers);

        AtomicReference<Deadline> captured = new AtomicReference<>();
        DeadlineWebFilter filter = new DeadlineWebFilter(codec, null);

        Mono<Void> result = filter.filter(exchange, capturingChain(captured));

        StepVerifier.create(result).verifyComplete();

        assertNotNull(captured.get(), "Deadline should be present in context");
        long remainingMs = captured.get().remainingMillis();
        assertTrue(remainingMs > 4000 && remainingMs <= 5000,
                "Expected ~5000ms remaining, got " + remainingMs);
    }

    @Test
    void fallsBackToDefaultDeadline() {
        HttpHeaders headers = new HttpHeaders();
        setupHeaders(headers);

        AtomicReference<Deadline> captured = new AtomicReference<>();
        DeadlineWebFilter filter = new DeadlineWebFilter(codec, Duration.ofSeconds(10));

        Mono<Void> result = filter.filter(exchange, capturingChain(captured));

        StepVerifier.create(result).verifyComplete();

        assertNotNull(captured.get(), "Deadline should be present from default");
        long remainingMs = captured.get().remainingMillis();
        assertTrue(remainingMs > 9000 && remainingMs <= 10000,
                "Expected ~10000ms remaining, got " + remainingMs);
    }

    @Test
    void noOpWhenNoHeaderAndNoDefault() {
        HttpHeaders headers = new HttpHeaders();
        setupHeaders(headers);

        AtomicReference<Deadline> captured = new AtomicReference<>();
        DeadlineWebFilter filter = new DeadlineWebFilter(codec, null);

        Mono<Void> result = filter.filter(exchange, capturingChain(captured));

        StepVerifier.create(result).verifyComplete();

        assertNull(captured.get(), "No deadline should be in context");
    }

    @Test
    void appliesServerMaxDeadlineCap() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "30000");
        setupHeaders(headers);

        ServerDeadlineConfig serverConfig = new ServerDeadlineConfig(Duration.ofSeconds(5));
        AtomicReference<Deadline> captured = new AtomicReference<>();
        DeadlineWebFilter filter = new DeadlineWebFilter(codec, null, serverConfig);

        Mono<Void> result = filter.filter(exchange, capturingChain(captured));

        StepVerifier.create(result).verifyComplete();

        assertNotNull(captured.get(), "Deadline should be present");
        long remainingMs = captured.get().remainingMillis();
        assertTrue(remainingMs > 4000 && remainingMs <= 5000,
                "Expected deadline capped to ~5000ms, got " + remainingMs);
    }

    @Test
    void noServerConfigUsesNoCeiling() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "30000");
        setupHeaders(headers);

        AtomicReference<Deadline> captured = new AtomicReference<>();
        DeadlineWebFilter filter = new DeadlineWebFilter(codec, null);

        Mono<Void> result = filter.filter(exchange, capturingChain(captured));

        StepVerifier.create(result).verifyComplete();

        assertNotNull(captured.get(), "Deadline should be present");
        long remainingMs = captured.get().remainingMillis();
        // 30000ms exceeds the 5-minute cap in Deadline.fromRemainingMillis,
        // but 30s is well under 5min so it should not be capped
        assertTrue(remainingMs > 29000 && remainingMs <= 30000,
                "Expected ~30000ms remaining (not capped), got " + remainingMs);
    }
}
