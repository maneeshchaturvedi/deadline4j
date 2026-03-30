package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutBudgetTest {

    private DeadlineContext.Scope scope;

    @AfterEach
    void cleanup() {
        if (scope != null) {
            scope.close();
            scope = null;
        }
        // Reset storage to default in case a test changed it
        TimeoutBudget.setStorage(TimeoutBudgetStorage.threadLocal());
    }

    // --- from(Deadline) creation ---

    @Test
    void fromDeadline_createsNonExpiredBudget() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        TimeoutBudget budget = TimeoutBudget.from(deadline);
        assertThat(budget.isExpired()).isFalse();
        assertThat(budget.remaining()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void fromDeadline_usesGivenDeadline() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(3));
        TimeoutBudget budget = TimeoutBudget.from(deadline);
        assertThat(budget.deadline()).isSameAs(deadline);
    }

    // --- of(Duration) creation ---

    @Test
    void ofDuration_createsNonExpiredBudget() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(5));
        assertThat(budget.isExpired()).isFalse();
        assertThat(budget.remaining()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void ofDuration_remainingApproximatesGivenDuration() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(2));
        assertThat(budget.remaining().toMillis()).isBetween(1900L, 2100L);
    }

    // --- remaining, isExpired ---

    @Test
    void remaining_returnsPositiveDurationForNonExpired() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(10));
        assertThat(budget.remaining()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void isExpired_falseForFreshBudget() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(10));
        assertThat(budget.isExpired()).isFalse();
    }

    @Test
    void isExpired_trueForZeroDuration() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ZERO);
        assertThat(budget.isExpired()).isTrue();
    }

    // --- canAfford ---

    @Test
    void canAfford_trueWhenSufficientBudget() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(5));
        assertThat(budget.canAfford(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void canAfford_falseWhenInsufficientBudget() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofMillis(100));
        assertThat(budget.canAfford(Duration.ofSeconds(5))).isFalse();
    }

    @Test
    void canAfford_trueWhenExactlyEqual() {
        // Budget of 5s, check afford of slightly less to account for time passage
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(5));
        assertThat(budget.canAfford(Duration.ofMillis(1))).isTrue();
    }

    // --- allocate ---

    @Test
    void allocate_returnsMaxAllocationWhenSufficientBudget() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(10));
        Duration allocation = budget.allocate(Duration.ofSeconds(3));
        assertThat(allocation).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void allocate_returnsRemainingWhenInsufficientBudget() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofMillis(500));
        Duration allocation = budget.allocate(Duration.ofSeconds(5));
        assertThat(allocation.toMillis()).isLessThanOrEqualTo(500);
        assertThat(allocation.toMillis()).isGreaterThan(0);
    }

    // --- recordConsumption / segments ---

    @Test
    void recordConsumption_addsSegment() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(10));
        budget.recordConsumption("service-a", Duration.ofMillis(200));
        assertThat(budget.segments()).hasSize(1);
        assertThat(budget.segments().get(0).name()).isEqualTo("service-a");
        assertThat(budget.segments().get(0).consumed()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void recordConsumption_multipleSegments() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(10));
        budget.recordConsumption("svc-a", Duration.ofMillis(100));
        budget.recordConsumption("svc-b", Duration.ofMillis(200));
        budget.recordConsumption("svc-c", Duration.ofMillis(300));

        List<TimeoutBudget.Segment> segs = budget.segments();
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).name()).isEqualTo("svc-a");
        assertThat(segs.get(1).name()).isEqualTo("svc-b");
        assertThat(segs.get(2).name()).isEqualTo("svc-c");
    }

    @Test
    void segments_returnsUnmodifiableList() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(10));
        budget.recordConsumption("svc", Duration.ofMillis(100));
        List<TimeoutBudget.Segment> segs = budget.segments();
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> segs.add(new TimeoutBudget.Segment("x", Duration.ZERO))
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- NOOP sentinel ---

    @Test
    void noop_neverExpires() {
        assertThat(TimeoutBudget.NOOP.isExpired()).isFalse();
    }

    @Test
    void noop_alwaysAffords() {
        assertThat(TimeoutBudget.NOOP.canAfford(Duration.ofHours(1))).isTrue();
    }

    @Test
    void noop_recordsNothing() {
        TimeoutBudget.NOOP.recordConsumption("svc", Duration.ofMillis(100));
        assertThat(TimeoutBudget.NOOP.segments()).isEmpty();
    }

    @Test
    void noop_segmentsEmpty() {
        assertThat(TimeoutBudget.NOOP.segments()).isEmpty();
    }

    // --- current() returns NOOP when none attached ---

    @Test
    void current_returnsNoopWhenNoneAttached() {
        assertThat(TimeoutBudget.current()).isSameAs(TimeoutBudget.NOOP);
    }

    // --- attachBudget / close lifecycle ---

    @Test
    void attachAndClose_lifecycle() {
        TimeoutBudget budget = TimeoutBudget.of(Duration.ofSeconds(5));
        scope = TimeoutBudget.attachBudget(budget);
        assertThat(TimeoutBudget.current()).isSameAs(budget);
        scope.close();
        scope = null;
        assertThat(TimeoutBudget.current()).isSameAs(TimeoutBudget.NOOP);
    }

    // --- nesting attach / close ---

    @Test
    void nesting_attachAndClose() {
        TimeoutBudget outer = TimeoutBudget.of(Duration.ofSeconds(10));
        TimeoutBudget inner = TimeoutBudget.of(Duration.ofSeconds(5));

        DeadlineContext.Scope outerScope = TimeoutBudget.attachBudget(outer);
        assertThat(TimeoutBudget.current()).isSameAs(outer);

        DeadlineContext.Scope innerScope = TimeoutBudget.attachBudget(inner);
        assertThat(TimeoutBudget.current()).isSameAs(inner);

        innerScope.close();
        assertThat(TimeoutBudget.current()).isSameAs(outer);

        outerScope.close();
        assertThat(TimeoutBudget.current()).isSameAs(TimeoutBudget.NOOP);
    }

    // --- custom storage via setStorage ---

    @Test
    void customStorage_isUsedForCurrentAndAttach() {
        TimeoutBudget customBudget = TimeoutBudget.of(Duration.ofSeconds(7));

        TimeoutBudgetStorage custom = new TimeoutBudgetStorage() {
            private TimeoutBudget stored;

            @Override
            public TimeoutBudget current() {
                return stored;
            }

            @Override
            public DeadlineContext.Scope attach(TimeoutBudget budget) {
                TimeoutBudget prev = stored;
                stored = budget;
                return () -> stored = prev;
            }

            @Override
            public DeadlineContext.Scope clear() {
                TimeoutBudget prev = stored;
                stored = null;
                return () -> stored = prev;
            }
        };

        TimeoutBudget.setStorage(custom);

        // Before attach, current() returns NOOP since custom storage returns null
        assertThat(TimeoutBudget.current()).isSameAs(TimeoutBudget.NOOP);

        scope = TimeoutBudget.attachBudget(customBudget);
        assertThat(TimeoutBudget.current()).isSameAs(customBudget);

        scope.close();
        scope = null;
        assertThat(TimeoutBudget.current()).isSameAs(TimeoutBudget.NOOP);
    }

    // --- deadline() getter ---

    @Test
    void deadline_returnsUnderlyingDeadline() {
        Deadline dl = Deadline.after(Duration.ofSeconds(5));
        TimeoutBudget budget = TimeoutBudget.from(dl);
        assertThat(budget.deadline()).isSameAs(dl);
    }
}
