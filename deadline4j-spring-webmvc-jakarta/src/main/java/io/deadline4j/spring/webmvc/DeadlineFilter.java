package io.deadline4j.spring.webmvc;

import io.deadline4j.*;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;

/**
 * Extracts deadline from incoming HTTP headers, attaches to DeadlineContext,
 * and creates a TimeoutBudget. Mirrors gRPC ServerImpl.createContext().
 *
 * <p>Registered at highest filter order for maximum remaining-time accuracy.
 *
 * <p><strong>WebMVC enforcement limitation:</strong> This filter provides
 * deadline propagation, adaptive timeouts, and pre-call enforcement. It does
 * NOT provide mid-call cancellation — that requires a non-blocking I/O model
 * (see deadline4j-spring-webflux for reactive cancellation support).
 */
public class DeadlineFilter implements Filter {

    private final DeadlineCodec codec;
    private final Duration defaultDeadline;           // null = no default
    private final EnforcementMode enforcementMode;
    private final ServerDeadlineConfig serverConfig;  // applies max-deadline cap

    public DeadlineFilter(DeadlineCodec codec, Duration defaultDeadline,
                          EnforcementMode enforcementMode,
                          ServerDeadlineConfig serverConfig) {
        this.codec = codec;
        this.defaultDeadline = defaultDeadline;
        this.enforcementMode = enforcementMode;
        this.serverConfig = serverConfig;
    }

    /** Convenience constructor without server config (no ceiling). */
    public DeadlineFilter(DeadlineCodec codec, Duration defaultDeadline,
                          EnforcementMode enforcementMode) {
        this(codec, defaultDeadline, enforcementMode, ServerDeadlineConfig.none());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        Deadline deadline = codec.extract(httpReq, HttpServletRequest::getHeader);

        if (deadline == null && defaultDeadline != null) {
            deadline = Deadline.after(defaultDeadline);
        }

        // Apply server-imposed ceiling
        if (deadline != null) {
            deadline = serverConfig.applyTo(deadline);
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
