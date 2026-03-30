package io.deadline4j.spring.webmvc;

import io.deadline4j.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadlineRestTemplateInterceptorTest {

    private static final URI TEST_URI = URI.create("http://downstream-service/api/data");
    private static final String SERVICE_NAME = "downstream-service";
    private static final byte[] EMPTY_BODY = new byte[0];

    @Mock private HttpRequest httpRequest;
    @Mock private ClientHttpRequestExecution execution;

    private HttpHeaders headers;
    private DeadlineContext.Scope deadlineScope;
    private DeadlineContext.Scope budgetScope;

    private AdaptiveTimeoutRegistry adaptiveRegistry;
    private DeadlineCodec codec;

    /** Simple stub ClientHttpResponse to avoid ByteBuddy issues on Java 25. */
    private static final ClientHttpResponse STUB_RESPONSE = new ClientHttpResponse() {
        @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(200); }
        @Override public String getStatusText() { return "OK"; }
        @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void close() {}
    };

    @BeforeEach
    void setUp() {
        headers = new HttpHeaders();
        lenient().when(httpRequest.getURI()).thenReturn(TEST_URI);
        lenient().when(httpRequest.getHeaders()).thenReturn(headers);
        codec = DeadlineCodec.remainingMillis();
        adaptiveRegistry = new AdaptiveTimeoutRegistry(
            name -> AdaptiveTimeoutConfig.builder().build()
        );
    }

    @AfterEach
    void tearDown() {
        if (budgetScope != null) {
            budgetScope.close();
            budgetScope = null;
        }
        if (deadlineScope != null) {
            deadlineScope.close();
            deadlineScope = null;
        }
    }

    private DeadlineRestTemplateInterceptor interceptorWith(EnforcementMode mode,
            ServicePriority priority, Duration minBudget) {
        ServiceConfig config = ServiceConfig.builder()
                .enforcement(mode)
                .priority(priority)
                .minBudgetRequired(minBudget)
                .build();
        ServiceConfigRegistry serviceConfigRegistry = new ServiceConfigRegistry(name -> config);
        return new DeadlineRestTemplateInterceptor(
                codec, adaptiveRegistry, ServiceNameResolver.byHost(), serviceConfigRegistry);
    }

    private DeadlineRestTemplateInterceptor interceptorWith(EnforcementMode mode) {
        return interceptorWith(mode, ServicePriority.REQUIRED, null);
    }

    @Test
    void propagatesDeadlineHeader() throws IOException {
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);
        interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        String remainingHeader = headers.getFirst("X-Deadline-Remaining-Ms");
        assertNotNull(remainingHeader, "X-Deadline-Remaining-Ms header should be set");
        long remainingMs = Long.parseLong(remainingHeader);
        assertTrue(remainingMs > 0 && remainingMs <= 5000,
                "Remaining ms should be positive and <= 5000, was: " + remainingMs);
    }

    @Test
    void recordsLatencyToAdaptiveTimeout() throws IOException {
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);
        long before = adaptiveRegistry.forService(SERVICE_NAME).sampleCount();

        interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        long after = adaptiveRegistry.forService(SERVICE_NAME).sampleCount();
        assertEquals(before + 1, after, "Sample count should increment by 1");
    }

    @Test
    void recordsBudgetConsumption() throws IOException {
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));
        deadlineScope = DeadlineContext.attach(deadline);
        TimeoutBudget budget = TimeoutBudget.from(deadline);
        budgetScope = TimeoutBudget.attachBudget(budget);
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);
        interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        assertEquals(1, budget.segments().size(), "Should have 1 segment");
        assertEquals(SERVICE_NAME, budget.segments().get(0).name());
    }

    @Test
    void autoDegradesOptionalCalls() throws IOException {
        // Deadline with only 100ms remaining, but service requires 10s minimum budget
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ofMillis(100)));

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(
                EnforcementMode.ENFORCE, ServicePriority.OPTIONAL, Duration.ofSeconds(10));

        ClientHttpResponse response = interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        assertTrue(response instanceof EmptyClientHttpResponse,
                "Should return EmptyClientHttpResponse for degraded optional call");
        assertEquals(SERVICE_NAME, ((EmptyClientHttpResponse) response).skippedService());
        verifyNoInteractions(execution);
    }

    @Test
    void throwsDeadlineExceededWhenExpired() {
        // Create an already-expired deadline
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ZERO));

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);

        DeadlineExceededException ex = assertThrows(DeadlineExceededException.class,
                () -> interceptor.intercept(httpRequest, EMPTY_BODY, execution));
        assertTrue(ex.getMessage().contains(SERVICE_NAME));
    }

    @Test
    void setsInternalTimeoutHeader() throws IOException {
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);
        interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        String internalTimeout = headers.getFirst("X-Deadline-Internal-Timeout-Ms");
        assertNotNull(internalTimeout, "X-Deadline-Internal-Timeout-Ms header should be set");
        long timeoutMs = Long.parseLong(internalTimeout);
        assertTrue(timeoutMs > 0, "Internal timeout should be positive, was: " + timeoutMs);
    }

    @Test
    void disabledModeStillRecordsLatency() throws IOException {
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.DISABLED);
        long before = adaptiveRegistry.forService(SERVICE_NAME).sampleCount();

        interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        long after = adaptiveRegistry.forService(SERVICE_NAME).sampleCount();
        assertEquals(before + 1, after, "Latency should be recorded even in DISABLED mode");
        assertNull(headers.getFirst("X-Deadline-Remaining-Ms"),
                "No deadline header should be propagated in DISABLED mode");
    }

    @Test
    void observeModePropagatesToNotEnforce() throws IOException {
        // Use an expired deadline — OBSERVE mode should still propagate but not throw
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ZERO));
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.OBSERVE);

        // Should NOT throw, even though deadline is expired
        ClientHttpResponse response = interceptor.intercept(httpRequest, EMPTY_BODY, execution);
        assertNotNull(response);

        // Header should still be propagated
        String remainingHeader = headers.getFirst("X-Deadline-Remaining-Ms");
        assertNotNull(remainingHeader, "Header should be propagated in OBSERVE mode");
    }

    @Test
    void noDeadlinePassthrough() throws IOException {
        // No deadline attached to context
        when(execution.execute(any(), any())).thenReturn(STUB_RESPONSE);

        // Use ENFORCE mode — but with no deadline, it should still pass through
        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);
        ClientHttpResponse response = interceptor.intercept(httpRequest, EMPTY_BODY, execution);

        assertNotNull(response);
        verify(execution).execute(httpRequest, EMPTY_BODY);
        assertNull(headers.getFirst("X-Deadline-Remaining-Ms"),
                "No deadline header should be set when no deadline in context");
        assertNull(headers.getFirst("X-Deadline-Internal-Timeout-Ms"),
                "No internal timeout header when no deadline in context");
    }

    @Test
    void recordsLatencyOnIOException() throws IOException {
        deadlineScope = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(5)));
        when(execution.execute(any(), any())).thenThrow(new IOException("connection refused"));

        DeadlineRestTemplateInterceptor interceptor = interceptorWith(EnforcementMode.ENFORCE);
        long before = adaptiveRegistry.forService(SERVICE_NAME).sampleCount();

        assertThrows(IOException.class,
                () -> interceptor.intercept(httpRequest, EMPTY_BODY, execution));

        long after = adaptiveRegistry.forService(SERVICE_NAME).sampleCount();
        assertEquals(before + 1, after, "Latency should be recorded even on IOException");
    }
}
