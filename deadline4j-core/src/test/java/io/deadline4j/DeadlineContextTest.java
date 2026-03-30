package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DeadlineContextTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        // Ensure a clean state: reset storage to default ThreadLocal
        DeadlineContext.setStorage(DeadlineContextStorage.threadLocal());
        // Clear any lingering deadline
        DeadlineContext.clear().close();
    }

    // --- current() ---

    @Test
    void current_returnsEmpty_whenNoDeadlineSet() {
        assertThat(DeadlineContext.current()).isEmpty();
    }

    @Test
    void current_returnsDeadline_whenAttached() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            Optional<Deadline> current = DeadlineContext.current();
            assertThat(current).isPresent().containsSame(deadline);
        }
    }

    @Test
    void current_returnsEmpty_afterScopeClosed() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        DeadlineContext.Scope scope = DeadlineContext.attach(deadline);
        scope.close();
        assertThat(DeadlineContext.current()).isEmpty();
    }

    // --- attach() ---

    @Test
    void attach_nullDeadlineThrowsNPE() {
        assertThatNullPointerException().isThrownBy(() -> DeadlineContext.attach(null));
    }

    @Test
    void attach_returnsScope() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            assertThat(scope).isNotNull();
        }
    }

    // --- Nesting ---

    @Test
    void attach_nesting_restoresPreviousOnClose() {
        Deadline outer = Deadline.after(Duration.ofSeconds(10), "outer");
        Deadline inner = Deadline.after(Duration.ofSeconds(1), "inner");

        try (DeadlineContext.Scope outerScope = DeadlineContext.attach(outer)) {
            assertThat(DeadlineContext.current()).containsSame(outer);

            try (DeadlineContext.Scope innerScope = DeadlineContext.attach(inner)) {
                assertThat(DeadlineContext.current()).containsSame(inner);
            }

            // Inner scope closed — should restore outer
            assertThat(DeadlineContext.current()).containsSame(outer);
        }

        // Outer scope closed — should be empty
        assertThat(DeadlineContext.current()).isEmpty();
    }

    @Test
    void attach_tripleNesting_restoresCorrectly() {
        Deadline d1 = Deadline.after(Duration.ofSeconds(10), "d1");
        Deadline d2 = Deadline.after(Duration.ofSeconds(5), "d2");
        Deadline d3 = Deadline.after(Duration.ofSeconds(1), "d3");

        try (DeadlineContext.Scope s1 = DeadlineContext.attach(d1)) {
            try (DeadlineContext.Scope s2 = DeadlineContext.attach(d2)) {
                try (DeadlineContext.Scope s3 = DeadlineContext.attach(d3)) {
                    assertThat(DeadlineContext.current()).containsSame(d3);
                }
                assertThat(DeadlineContext.current()).containsSame(d2);
            }
            assertThat(DeadlineContext.current()).containsSame(d1);
        }
        assertThat(DeadlineContext.current()).isEmpty();
    }

    // --- clear() ---

    @Test
    void clear_removesDeadline() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            assertThat(DeadlineContext.current()).isPresent();

            try (DeadlineContext.Scope clearScope = DeadlineContext.clear()) {
                assertThat(DeadlineContext.current()).isEmpty();
            }

            // Clear scope closed — previous deadline restored
            assertThat(DeadlineContext.current()).containsSame(deadline);
        }
    }

    @Test
    void clear_withNoDeadline_isNoOp() {
        try (DeadlineContext.Scope clearScope = DeadlineContext.clear()) {
            assertThat(DeadlineContext.current()).isEmpty();
        }
        assertThat(DeadlineContext.current()).isEmpty();
    }

    // --- capture() ---

    @Test
    void capture_returnsNull_whenNoDeadline() {
        assertThat(DeadlineContext.capture()).isNull();
    }

    @Test
    void capture_returnsCurrentDeadline() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            assertThat(DeadlineContext.capture()).isSameAs(deadline);
        }
    }

    // --- remaining() ---

    @Test
    void remaining_returnsDefault_whenNoDeadline() {
        Duration defaultDuration = Duration.ofSeconds(30);
        assertThat(DeadlineContext.remaining(defaultDuration)).isEqualTo(defaultDuration);
    }

    @Test
    void remaining_returnsDeadlineRemaining_whenSet() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            Duration remaining = DeadlineContext.remaining(Duration.ofSeconds(30));
            assertThat(remaining).isLessThanOrEqualTo(Duration.ofSeconds(5));
            assertThat(remaining).isGreaterThan(Duration.ZERO);
        }
    }

    // --- isExpired() ---

    @Test
    void isExpired_false_whenNoDeadline() {
        assertThat(DeadlineContext.isExpired()).isFalse();
    }

    @Test
    void isExpired_false_whenDeadlineNotExpired() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            assertThat(DeadlineContext.isExpired()).isFalse();
        }
    }

    @Test
    void isExpired_true_whenDeadlineExpired() {
        Deadline deadline = Deadline.after(Duration.ZERO);
        try (DeadlineContext.Scope scope = DeadlineContext.attach(deadline)) {
            assertThat(DeadlineContext.isExpired()).isTrue();
        }
    }

    // --- setStorage() ---

    @Test
    void setStorage_nullThrowsNPE() {
        assertThatNullPointerException().isThrownBy(() -> DeadlineContext.setStorage(null));
    }

    @Test
    void setStorage_usesCustomStorage() {
        Deadline fixed = Deadline.after(Duration.ofSeconds(99));
        DeadlineContextStorage custom = new DeadlineContextStorage() {
            @Override
            public Deadline current() {
                return fixed;
            }

            @Override
            public DeadlineContext.Scope attach(Deadline deadline) {
                return () -> {};
            }

            @Override
            public DeadlineContext.Scope clear() {
                return () -> {};
            }
        };

        DeadlineContext.setStorage(custom);
        assertThat(DeadlineContext.current()).containsSame(fixed);
    }
}
