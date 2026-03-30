package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineTestSupportTest {

    @AfterEach
    void cleanUp() {
        // Ensure no deadline leaks between tests
        DeadlineContext.current().ifPresent(d -> {
            // Force clear via clear() if something leaked
        });
    }

    @Test
    void withDeadline_setsDeadlineAndCleansUp() {
        // Verify no deadline before
        assertThat(DeadlineContext.current()).isEmpty();

        AtomicBoolean ranInside = new AtomicBoolean(false);

        DeadlineTestSupport.withDeadline(Duration.ofSeconds(5), () -> {
            Optional<Deadline> current = DeadlineContext.current();
            assertThat(current).isPresent();
            assertThat(current.get().isExpired()).isFalse();
            assertThat(current.get().remainingMillis()).isGreaterThan(0);
            ranInside.set(true);
        });

        // Verify block ran
        assertThat(ranInside).isTrue();

        // Verify deadline was cleaned up
        assertThat(DeadlineContext.current()).isEmpty();
    }

    @Test
    void withExpiredDeadline_createsExpiredDeadline() {
        AtomicBoolean ranInside = new AtomicBoolean(false);

        DeadlineTestSupport.withExpiredDeadline(() -> {
            assertThat(DeadlineContext.current()).isPresent();
            assertThat(DeadlineContext.isExpired()).isTrue();
            assertThat(DeadlineContext.current().get().remainingMillis()).isEqualTo(0);
            ranInside.set(true);
        });

        assertThat(ranInside).isTrue();
        assertThat(DeadlineContext.current()).isEmpty();
    }

    @Test
    void withoutDeadline_clearsAnyExistingDeadline() {
        // Set up a deadline first
        try (DeadlineContext.Scope outer = DeadlineContext.attach(Deadline.after(Duration.ofSeconds(10)))) {
            assertThat(DeadlineContext.current()).isPresent();

            AtomicBoolean ranInside = new AtomicBoolean(false);

            DeadlineTestSupport.withoutDeadline(() -> {
                assertThat(DeadlineContext.current()).isEmpty();
                ranInside.set(true);
            });

            assertThat(ranInside).isTrue();

            // After withoutDeadline, the outer deadline should be restored
            assertThat(DeadlineContext.current()).isPresent();
        }
    }

    @Test
    void assertWithinBudget_passesForFastOperations() {
        // Should not throw for a fast no-op
        DeadlineTestSupport.assertWithinBudget(Duration.ofSeconds(5), () -> {
            // Fast operation — no-op
        });
    }

    @Test
    void assertWithinBudget_throwsAssertionErrorForSlowOperations() {
        assertThatThrownBy(() ->
                DeadlineTestSupport.assertWithinBudget(Duration.ofMillis(10), () -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
        ).isInstanceOf(AssertionError.class)
                .hasMessageContaining("exceeded budget");
    }

    @Test
    void assertWithinBudget_setsDeadlineDuringExecution() {
        DeadlineTestSupport.assertWithinBudget(Duration.ofSeconds(5), () -> {
            assertThat(DeadlineContext.current()).isPresent();
            assertThat(DeadlineContext.current().get().isExpired()).isFalse();
        });

        // Cleaned up after
        assertThat(DeadlineContext.current()).isEmpty();
    }

    @Test
    void nestedWithDeadline_innerOverridesOuter() {
        DeadlineTestSupport.withDeadline(Duration.ofSeconds(10), () -> {
            long outerRemaining = DeadlineContext.current().get().remainingMillis();

            DeadlineTestSupport.withDeadline(Duration.ofMillis(500), () -> {
                long innerRemaining = DeadlineContext.current().get().remainingMillis();
                // Inner deadline is shorter
                assertThat(innerRemaining).isLessThan(outerRemaining);
            });

            // After inner scope closes, outer deadline is restored
            assertThat(DeadlineContext.current()).isPresent();
            assertThat(DeadlineContext.current().get().remainingMillis())
                    .isGreaterThan(500); // outer should still have > 500ms
        });

        assertThat(DeadlineContext.current()).isEmpty();
    }

    @Test
    void nestedWithDeadlineAndWithoutDeadline_worksCorrectly() {
        DeadlineTestSupport.withDeadline(Duration.ofSeconds(10), () -> {
            assertThat(DeadlineContext.current()).isPresent();

            DeadlineTestSupport.withoutDeadline(() -> {
                assertThat(DeadlineContext.current()).isEmpty();

                // Nest another withDeadline inside withoutDeadline
                DeadlineTestSupport.withDeadline(Duration.ofMillis(200), () -> {
                    assertThat(DeadlineContext.current()).isPresent();
                    assertThat(DeadlineContext.current().get().remainingMillis())
                            .isLessThanOrEqualTo(200);
                });

                // Back to no deadline
                assertThat(DeadlineContext.current()).isEmpty();
            });

            // Back to outer deadline
            assertThat(DeadlineContext.current()).isPresent();
        });

        // Fully cleaned up
        assertThat(DeadlineContext.current()).isEmpty();
    }
}
