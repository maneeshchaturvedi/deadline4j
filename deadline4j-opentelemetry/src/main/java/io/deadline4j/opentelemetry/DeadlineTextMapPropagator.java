package io.deadline4j.opentelemetry;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Propagates deadline as OTel baggage across process boundaries.
 *
 * <p>Baggage keys:
 * <ul>
 *   <li>{@code deadline-remaining-ms} — remaining time in milliseconds</li>
 *   <li>{@code deadline-id} — correlation ID</li>
 * </ul>
 */
public class DeadlineTextMapPropagator implements TextMapPropagator {

    static final String BAGGAGE_REMAINING = "deadline-remaining-ms";
    static final String BAGGAGE_ID = "deadline-id";
    static final ContextKey<Deadline> DEADLINE_CONTEXT_KEY =
        ContextKey.named("deadline4j.deadline");

    private static final List<String> FIELDS =
        Collections.unmodifiableList(Arrays.asList(BAGGAGE_REMAINING, BAGGAGE_ID));

    @Override
    public Collection<String> fields() {
        return FIELDS;
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        // Read from OTel context first, then fall back to DeadlineContext
        Deadline deadline = context.get(DEADLINE_CONTEXT_KEY);
        if (deadline == null) {
            deadline = DeadlineContext.capture();
        }
        if (deadline == null || carrier == null || setter == null) return;

        setter.set(carrier, BAGGAGE_REMAINING, String.valueOf(deadline.remainingMillis()));
        deadline.id().ifPresent(id -> setter.set(carrier, BAGGAGE_ID, id));
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        if (carrier == null || getter == null) return context;

        String raw = getter.get(carrier, BAGGAGE_REMAINING);
        if (raw == null || raw.isEmpty()) return context;

        try {
            long remainingMs = Long.parseLong(raw);
            if (remainingMs <= 0) return context;

            String id = getter.get(carrier, BAGGAGE_ID);
            Deadline deadline = Deadline.fromRemainingMillis(remainingMs, id);
            return context.with(DEADLINE_CONTEXT_KEY, deadline);
        } catch (NumberFormatException e) {
            return context;
        }
    }
}
