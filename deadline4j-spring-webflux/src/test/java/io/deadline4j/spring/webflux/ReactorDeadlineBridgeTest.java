package io.deadline4j.spring.webflux;

import io.deadline4j.Deadline;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ReactorDeadlineBridgeTest {

    @Test
    void withDeadline_putsDeadlineInContext() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        Context ctx = ReactorDeadlineBridge.withDeadline(Context.empty(), deadline);

        Deadline retrieved = ReactorDeadlineBridge.fromContext(ctx);
        assertNotNull(retrieved);
        assertSame(deadline, retrieved);
    }

    @Test
    void fromContext_returnsNullWhenAbsent() {
        Deadline result = ReactorDeadlineBridge.fromContext(Context.empty());
        assertNull(result);
    }

    @Test
    void roundTrip() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));
        Context ctx = ReactorDeadlineBridge.withDeadline(Context.empty(), deadline);
        Deadline back = ReactorDeadlineBridge.fromContext(ctx);
        assertSame(deadline, back, "Round-tripped deadline should be the same instance");
    }
}
