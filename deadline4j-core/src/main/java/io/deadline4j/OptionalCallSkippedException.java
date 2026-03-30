package io.deadline4j;

/**
 * Thrown internally when an optional call is skipped due to insufficient
 * budget. Caught by the Feign fallback factory or RestTemplate interceptor
 * to return a default value. Never escapes to application code.
 */
public class OptionalCallSkippedException extends DeadlineExceededException {

    public OptionalCallSkippedException(String serviceName, long budgetRemainingMs) {
        super("Skipping optional call to " + serviceName
            + " — only " + budgetRemainingMs + "ms remaining",
            serviceName, budgetRemainingMs);
    }
}
