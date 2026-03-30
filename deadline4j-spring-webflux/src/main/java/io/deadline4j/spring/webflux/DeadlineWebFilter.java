package io.deadline4j.spring.webflux;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineCodec;
import io.deadline4j.ServerDeadlineConfig;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive equivalent of the servlet DeadlineFilter.
 *
 * <p>Extracts a deadline from inbound HTTP headers using a
 * {@link DeadlineCodec} and places it into Reactor's Context so that
 * downstream operators can access it via
 * {@link ReactorDeadlineBridge#fromContext}.
 *
 * <p>If no header is present but a {@code defaultDeadline} is configured,
 * a fresh deadline is created. An optional {@link ServerDeadlineConfig}
 * caps the effective deadline to a server-imposed ceiling.
 */
public class DeadlineWebFilter implements WebFilter {

    private final DeadlineCodec codec;
    private final Duration defaultDeadline;
    private final ServerDeadlineConfig serverConfig;

    /**
     * Full constructor.
     *
     * @param codec           codec used to extract deadlines from headers
     * @param defaultDeadline fallback deadline duration when no header is present (nullable)
     * @param serverConfig    server-side ceiling configuration
     */
    public DeadlineWebFilter(DeadlineCodec codec, Duration defaultDeadline,
                             ServerDeadlineConfig serverConfig) {
        this.codec = codec;
        this.defaultDeadline = defaultDeadline;
        this.serverConfig = serverConfig;
    }

    /**
     * Convenience constructor with no server-side ceiling.
     *
     * @param codec           codec used to extract deadlines from headers
     * @param defaultDeadline fallback deadline duration when no header is present (nullable)
     */
    public DeadlineWebFilter(DeadlineCodec codec, Duration defaultDeadline) {
        this(codec, defaultDeadline, ServerDeadlineConfig.none());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Deadline deadline = codec.extract(
                exchange.getRequest().getHeaders(),
                (headers, key) -> headers.getFirst(key));

        if (deadline == null && defaultDeadline != null) {
            deadline = Deadline.after(defaultDeadline);
        }

        if (deadline != null) {
            deadline = serverConfig.applyTo(deadline);
        }

        if (deadline != null) {
            Deadline d = deadline;
            return chain.filter(exchange)
                    .contextWrite(ctx -> ctx.put(ReactorDeadlineBridge.CONTEXT_KEY, d));
        }
        return chain.filter(exchange);
    }
}
