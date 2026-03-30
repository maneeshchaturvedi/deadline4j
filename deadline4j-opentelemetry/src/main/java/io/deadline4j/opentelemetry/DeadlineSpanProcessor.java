package io.deadline4j.opentelemetry;

import io.deadline4j.Deadline;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;

/**
 * Records deadline4j attributes on OpenTelemetry spans.
 */
public final class DeadlineSpanProcessor {

    public static final AttributeKey<Long> REMAINING_MS =
        AttributeKey.longKey("deadline4j.remaining_ms");
    public static final AttributeKey<Double> BUDGET_CONSUMED =
        AttributeKey.doubleKey("deadline4j.budget_consumed");
    public static final AttributeKey<Boolean> EXCEEDED =
        AttributeKey.booleanKey("deadline4j.exceeded");
    public static final AttributeKey<Boolean> CALL_SKIPPED =
        AttributeKey.booleanKey("deadline4j.call_skipped");

    private DeadlineSpanProcessor() {}

    /** Set remaining_ms attribute at span start. */
    public static void onSpanStart(Span span, Deadline deadline) {
        if (span != null && deadline != null) {
            span.setAttribute(REMAINING_MS, deadline.remainingMillis());
        }
    }

    /** Set exceeded and budget_consumed attributes at span end. */
    public static void onSpanEnd(Span span, Deadline deadline, double budgetConsumedRatio) {
        if (span != null && deadline != null) {
            span.setAttribute(EXCEEDED, deadline.isExpired());
            span.setAttribute(BUDGET_CONSUMED, budgetConsumedRatio);
        }
    }

    /** Mark a call as skipped due to insufficient budget. */
    public static void markCallSkipped(Span span) {
        if (span != null) {
            span.setAttribute(CALL_SKIPPED, true);
        }
    }
}
