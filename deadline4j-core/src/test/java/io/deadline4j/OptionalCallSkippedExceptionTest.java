package io.deadline4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalCallSkippedExceptionTest {

    @Test
    void messageFormat_includesServiceNameAndRemainingMs() {
        OptionalCallSkippedException ex =
            new OptionalCallSkippedException("recommendation-svc", 50);
        assertThat(ex.getMessage())
            .contains("recommendation-svc")
            .contains("50");
    }

    @Test
    void inheritsFromDeadlineExceededException() {
        OptionalCallSkippedException ex =
            new OptionalCallSkippedException("svc", 100);
        assertThat(ex).isInstanceOf(DeadlineExceededException.class);
    }

    @Test
    void serviceName_propagated() {
        OptionalCallSkippedException ex =
            new OptionalCallSkippedException("analytics-svc", 75);
        assertThat(ex.serviceName()).isEqualTo("analytics-svc");
    }

    @Test
    void budgetRemainingMs_propagated() {
        OptionalCallSkippedException ex =
            new OptionalCallSkippedException("analytics-svc", 75);
        assertThat(ex.budgetRemainingMs()).isEqualTo(75);
    }
}
