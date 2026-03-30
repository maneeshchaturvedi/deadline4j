package io.deadline4j.opentelemetry;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineTextMapPropagatorTest {

    private final DeadlineTextMapPropagator propagator = new DeadlineTextMapPropagator();

    private final TextMapSetter<Map<String, String>> setter = Map::put;

    private final TextMapGetter<Map<String, String>> getter = new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private DeadlineContext.Scope deadlineScope;

    @BeforeEach
    void setUp() {
        // Ensure no deadline is attached
    }

    @AfterEach
    void tearDown() {
        if (deadlineScope != null) {
            deadlineScope.close();
            deadlineScope = null;
        }
    }

    @Test
    void fields_returnsBothBaggageKeys() {
        assertThat(propagator.fields()).containsExactlyInAnyOrder(
            DeadlineTextMapPropagator.BAGGAGE_REMAINING,
            DeadlineTextMapPropagator.BAGGAGE_ID
        );
    }

    @Test
    void inject_writesRemainingMsToCarrier() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "test-id");
        deadlineScope = DeadlineContext.attach(deadline);

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.current(), carrier, setter);

        assertThat(carrier).containsKey(DeadlineTextMapPropagator.BAGGAGE_REMAINING);
        long remaining = Long.parseLong(carrier.get(DeadlineTextMapPropagator.BAGGAGE_REMAINING));
        assertThat(remaining).isGreaterThan(0);
    }

    @Test
    void inject_writesDeadlineIdWhenPresent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "correlation-123");
        deadlineScope = DeadlineContext.attach(deadline);

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.current(), carrier, setter);

        assertThat(carrier.get(DeadlineTextMapPropagator.BAGGAGE_ID))
            .isEqualTo("correlation-123");
    }

    @Test
    void inject_doesNotWriteIdWhenAbsent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        deadlineScope = DeadlineContext.attach(deadline);

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.current(), carrier, setter);

        assertThat(carrier).doesNotContainKey(DeadlineTextMapPropagator.BAGGAGE_ID);
    }

    @Test
    void inject_withNoDeadline_isNoOp() {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.current(), carrier, setter);

        assertThat(carrier).isEmpty();
    }

    @Test
    void inject_fromOtelContext_takesContextDeadline() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(10), "otel-ctx");
        Context ctx = Context.current().with(DeadlineTextMapPropagator.DEADLINE_CONTEXT_KEY, deadline);

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(ctx, carrier, setter);

        assertThat(carrier).containsKey(DeadlineTextMapPropagator.BAGGAGE_REMAINING);
        assertThat(carrier.get(DeadlineTextMapPropagator.BAGGAGE_ID)).isEqualTo("otel-ctx");
    }

    @Test
    void extract_createsDeadlineFromCarrier() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put(DeadlineTextMapPropagator.BAGGAGE_REMAINING, "5000");

        Context result = propagator.extract(Context.current(), carrier, getter);

        Deadline deadline = result.get(DeadlineTextMapPropagator.DEADLINE_CONTEXT_KEY);
        assertThat(deadline).isNotNull();
        assertThat(deadline.remainingMillis()).isGreaterThan(0);
    }

    @Test
    void extract_withId_populatesDeadlineId() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put(DeadlineTextMapPropagator.BAGGAGE_REMAINING, "5000");
        carrier.put(DeadlineTextMapPropagator.BAGGAGE_ID, "req-456");

        Context result = propagator.extract(Context.current(), carrier, getter);

        Deadline deadline = result.get(DeadlineTextMapPropagator.DEADLINE_CONTEXT_KEY);
        assertThat(deadline).isNotNull();
        assertThat(deadline.id()).hasValue("req-456");
    }

    @Test
    void extract_missingKey_returnsOriginalContext() {
        Map<String, String> carrier = new HashMap<>();
        Context original = Context.current();

        Context result = propagator.extract(original, carrier, getter);

        assertThat(result).isSameAs(original);
    }

    @Test
    void extract_invalidNumber_returnsOriginalContext() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put(DeadlineTextMapPropagator.BAGGAGE_REMAINING, "not-a-number");
        Context original = Context.current();

        Context result = propagator.extract(original, carrier, getter);

        assertThat(result).isSameAs(original);
    }

    @Test
    void extract_zeroRemaining_returnsOriginalContext() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put(DeadlineTextMapPropagator.BAGGAGE_REMAINING, "0");
        Context original = Context.current();

        Context result = propagator.extract(original, carrier, getter);

        assertThat(result).isSameAs(original);
    }

    @Test
    void extract_negativeRemaining_returnsOriginalContext() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put(DeadlineTextMapPropagator.BAGGAGE_REMAINING, "-100");
        Context original = Context.current();

        Context result = propagator.extract(original, carrier, getter);

        assertThat(result).isSameAs(original);
    }

    @Test
    void roundTrip_injectThenExtract_preservesDeadline() {
        Deadline original = Deadline.after(Duration.ofSeconds(3), "round-trip");
        Context otelCtx = Context.current().with(DeadlineTextMapPropagator.DEADLINE_CONTEXT_KEY, original);

        // Inject
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(otelCtx, carrier, setter);

        // Extract
        Context extractedCtx = propagator.extract(Context.current(), carrier, getter);
        Deadline extracted = extractedCtx.get(DeadlineTextMapPropagator.DEADLINE_CONTEXT_KEY);

        assertThat(extracted).isNotNull();
        assertThat(extracted.id()).hasValue("round-trip");
        assertThat(extracted.remainingMillis()).isGreaterThan(0);
        // Allow some tolerance for time elapsed during the round-trip
        assertThat(extracted.remainingMillis()).isLessThanOrEqualTo(3000);
    }
}
