package io.deadline4j.spring.webmvc;

import io.deadline4j.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadlineFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    private final DeadlineCodec codec = DeadlineCodec.remainingMillis();

    @BeforeEach
    void clearContext() {
        // Ensure no leftover context from prior tests
    }

    @Test
    void extractsDeadlineFromHeader() throws Exception {
        when(request.getHeader("X-Deadline-Remaining-Ms")).thenReturn("5000");

        AtomicReference<Optional<Deadline>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(DeadlineContext.current());
            return null;
        }).when(chain).doFilter(any(), any());

        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE);
        filter.doFilter(request, response, chain);

        assertTrue(captured.get().isPresent(), "Deadline should be present during chain execution");
        long remainingMs = captured.get().get().remainingMillis();
        assertTrue(remainingMs > 4000 && remainingMs <= 5000,
                "Expected ~5000ms remaining, got " + remainingMs);
    }

    @Test
    void fallsBackToDefaultDeadline() throws Exception {
        // No header set — returns null for any getHeader call
        when(request.getHeader(any())).thenReturn(null);

        AtomicReference<Optional<Deadline>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(DeadlineContext.current());
            return null;
        }).when(chain).doFilter(any(), any());

        DeadlineFilter filter = new DeadlineFilter(codec, Duration.ofSeconds(10), EnforcementMode.ENFORCE);
        filter.doFilter(request, response, chain);

        assertTrue(captured.get().isPresent(), "Deadline should be present from default");
        long remainingMs = captured.get().get().remainingMillis();
        assertTrue(remainingMs > 9000 && remainingMs <= 10000,
                "Expected ~10000ms remaining, got " + remainingMs);
    }

    @Test
    void noOpWhenNoHeaderAndNoDefault() throws Exception {
        when(request.getHeader(any())).thenReturn(null);

        AtomicReference<Optional<Deadline>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(DeadlineContext.current());
            return null;
        }).when(chain).doFilter(any(), any());

        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE);
        filter.doFilter(request, response, chain);

        assertTrue(captured.get().isEmpty(), "No deadline should be present when no header and no default");
    }

    @Test
    void appliesServerMaxDeadlineCap() throws Exception {
        when(request.getHeader("X-Deadline-Remaining-Ms")).thenReturn("30000");

        ServerDeadlineConfig serverConfig = new ServerDeadlineConfig(Duration.ofSeconds(5));

        AtomicReference<Optional<Deadline>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(DeadlineContext.current());
            return null;
        }).when(chain).doFilter(any(), any());

        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE, serverConfig);
        filter.doFilter(request, response, chain);

        assertTrue(captured.get().isPresent(), "Deadline should be present");
        long remainingMs = captured.get().get().remainingMillis();
        assertTrue(remainingMs <= 5100,
                "Expected deadline capped to ~5000ms, got " + remainingMs);
        assertTrue(remainingMs > 4000,
                "Expected deadline around 5000ms, got " + remainingMs);
    }

    @Test
    void cleansUpContextAfterChain() throws Exception {
        when(request.getHeader("X-Deadline-Remaining-Ms")).thenReturn("5000");

        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE);
        filter.doFilter(request, response, chain);

        assertTrue(DeadlineContext.current().isEmpty(),
                "DeadlineContext should be empty after filter completes");
    }

    @Test
    void cleansUpContextOnChainException() throws Exception {
        when(request.getHeader("X-Deadline-Remaining-Ms")).thenReturn("5000");

        doThrow(new ServletException("boom")).when(chain).doFilter(any(), any());

        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE);

        assertThrows(ServletException.class, () ->
                filter.doFilter(request, response, chain));

        assertTrue(DeadlineContext.current().isEmpty(),
                "DeadlineContext should be cleaned up even when chain throws");
    }

    @Test
    void budgetIsAttachedAlongsideDeadline() throws Exception {
        when(request.getHeader("X-Deadline-Remaining-Ms")).thenReturn("5000");

        AtomicReference<TimeoutBudget> capturedBudget = new AtomicReference<>();
        doAnswer(inv -> {
            capturedBudget.set(TimeoutBudget.current());
            return null;
        }).when(chain).doFilter(any(), any());

        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE);
        filter.doFilter(request, response, chain);

        assertNotNull(capturedBudget.get(), "TimeoutBudget should be present");
        assertNotSame(TimeoutBudget.NOOP, capturedBudget.get(),
                "TimeoutBudget should not be NOOP during chain execution");
    }

    @Test
    void noServerConfigUsesNoCeiling() throws Exception {
        when(request.getHeader("X-Deadline-Remaining-Ms")).thenReturn("30000");

        AtomicReference<Optional<Deadline>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(DeadlineContext.current());
            return null;
        }).when(chain).doFilter(any(), any());

        // Use convenience constructor (no ServerDeadlineConfig)
        DeadlineFilter filter = new DeadlineFilter(codec, null, EnforcementMode.ENFORCE);
        filter.doFilter(request, response, chain);

        assertTrue(captured.get().isPresent(), "Deadline should be present");
        long remainingMs = captured.get().get().remainingMillis();
        // Note: Deadline.fromRemainingMillis caps at 300,000ms (5 min), and 30000 < 300000
        // so 30000ms should pass through uncapped
        assertTrue(remainingMs > 29000,
                "Expected deadline not capped (convenience constructor), got " + remainingMs);
    }
}
