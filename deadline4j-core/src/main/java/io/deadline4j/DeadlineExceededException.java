package io.deadline4j;

/**
 * Thrown when a deadline has expired before or during a downstream call.
 * Extends RuntimeException — recoverable, services should catch for
 * fallback logic (or let the framework handle it).
 */
public class DeadlineExceededException extends RuntimeException {

    private final String serviceName;
    private final long budgetRemainingMs;

    public DeadlineExceededException(String message) {
        this(message, null, -1);
    }

    public DeadlineExceededException(String message, String serviceName,
                                      long budgetRemainingMs) {
        super(message);
        this.serviceName = serviceName;
        this.budgetRemainingMs = budgetRemainingMs;
    }

    /** Name of the service that was being called, or null. */
    public String serviceName() {
        return serviceName;
    }

    /** Budget remaining in ms at the time of the exception, or -1 if unknown. */
    public long budgetRemainingMs() {
        return budgetRemainingMs;
    }
}
