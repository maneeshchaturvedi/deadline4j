package io.deadline4j.opentelemetry;

import io.deadline4j.Deadline;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineSpanProcessorTest {

    /**
     * A minimal Span implementation that records setAttribute calls.
     */
    static class RecordingSpan implements Span {
        final Map<AttributeKey<?>, Object> attributes = new HashMap<>();

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            attributes.put(key, value);
            return this;
        }

        @Override public Span setStatus(StatusCode statusCode, String description) { return this; }
        @Override public Span setStatus(StatusCode statusCode) { return this; }
        @Override public Span recordException(Throwable exception) { return this; }
        @Override public Span recordException(Throwable exception, io.opentelemetry.api.common.Attributes additionalAttributes) { return this; }
        @Override public Span updateName(String name) { return this; }
        @Override public Span addEvent(String name) { return this; }
        @Override public Span addEvent(String name, long timestamp, java.util.concurrent.TimeUnit unit) { return this; }
        @Override public Span addEvent(String name, io.opentelemetry.api.common.Attributes attributes) { return this; }
        @Override public Span addEvent(String name, io.opentelemetry.api.common.Attributes attributes, long timestamp, java.util.concurrent.TimeUnit unit) { return this; }
        @Override public void end() {}
        @Override public void end(long timestamp, java.util.concurrent.TimeUnit unit) {}
        @Override public SpanContext getSpanContext() { return SpanContext.getInvalid(); }
        @Override public boolean isRecording() { return true; }
    }

    @Test
    void onSpanStart_setsRemainingMs() {
        RecordingSpan span = new RecordingSpan();
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));

        DeadlineSpanProcessor.onSpanStart(span, deadline);

        Long remaining = (Long) span.attributes.get(DeadlineSpanProcessor.REMAINING_MS);
        assertThat(remaining).isNotNull().isGreaterThan(0);
    }

    @Test
    void onSpanStart_nullDeadline_isNoOp() {
        RecordingSpan span = new RecordingSpan();
        DeadlineSpanProcessor.onSpanStart(span, null);
        assertThat(span.attributes).isEmpty();
    }

    @Test
    void onSpanStart_nullSpan_isNoOp() {
        // Should not throw
        DeadlineSpanProcessor.onSpanStart(null, Deadline.after(Duration.ofSeconds(1)));
    }

    @Test
    void onSpanEnd_setsExceededAndBudgetConsumed() {
        RecordingSpan span = new RecordingSpan();
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));

        DeadlineSpanProcessor.onSpanEnd(span, deadline, 0.45);

        Boolean exceeded = (Boolean) span.attributes.get(DeadlineSpanProcessor.EXCEEDED);
        assertThat(exceeded).isFalse();
        Double consumed = (Double) span.attributes.get(DeadlineSpanProcessor.BUDGET_CONSUMED);
        assertThat(consumed).isEqualTo(0.45);
    }

    @Test
    void onSpanEnd_expiredDeadline_setsExceededTrue() {
        RecordingSpan span = new RecordingSpan();
        Deadline deadline = Deadline.after(Duration.ZERO);

        DeadlineSpanProcessor.onSpanEnd(span, deadline, 1.2);

        Boolean exceeded = (Boolean) span.attributes.get(DeadlineSpanProcessor.EXCEEDED);
        assertThat(exceeded).isTrue();
        Double consumed = (Double) span.attributes.get(DeadlineSpanProcessor.BUDGET_CONSUMED);
        assertThat(consumed).isEqualTo(1.2);
    }

    @Test
    void markCallSkipped_setsCallSkippedTrue() {
        RecordingSpan span = new RecordingSpan();

        DeadlineSpanProcessor.markCallSkipped(span);

        Boolean skipped = (Boolean) span.attributes.get(DeadlineSpanProcessor.CALL_SKIPPED);
        assertThat(skipped).isTrue();
    }

    @Test
    void markCallSkipped_nullSpan_isNoOp() {
        // Should not throw
        DeadlineSpanProcessor.markCallSkipped(null);
    }
}
