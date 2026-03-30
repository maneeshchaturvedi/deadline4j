package io.deadline4j.it.core;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineContext;
import io.deadline4j.TimeoutBudget;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests sequential budget exhaustion with real elapsed time.
 */
class BudgetExhaustionSequentialTest {

    @Test
    void budgetExhaustsAfterSequentialCalls() throws InterruptedException {
        Deadline deadline = Deadline.after(Duration.ofMillis(1000));
        TimeoutBudget budget = TimeoutBudget.from(deadline);

        try (DeadlineContext.Scope ds = DeadlineContext.attach(deadline);
             DeadlineContext.Scope bs = TimeoutBudget.attachBudget(budget)) {

            // Call 1: ~300ms
            long beforeCall1 = deadline.remainingMillis();
            Thread.sleep(300);
            budget.recordConsumption("call-1", Duration.ofMillis(300));
            long afterCall1 = deadline.remainingMillis();
            assertThat(afterCall1).isBetween(600L, 750L);

            // Call 2: ~300ms
            Thread.sleep(300);
            budget.recordConsumption("call-2", Duration.ofMillis(300));
            long afterCall2 = deadline.remainingMillis();
            assertThat(afterCall2).isBetween(300L, 450L);

            // Call 3: ~300ms
            Thread.sleep(300);
            budget.recordConsumption("call-3", Duration.ofMillis(300));
            long afterCall3 = deadline.remainingMillis();
            assertThat(afterCall3).isBetween(0L, 150L);

            // Verify 3 segments recorded
            assertThat(budget.segments()).hasSize(3);
            assertThat(budget.segments().get(0).name()).isEqualTo("call-1");
            assertThat(budget.segments().get(1).name()).isEqualTo("call-2");
            assertThat(budget.segments().get(2).name()).isEqualTo("call-3");

            // Call 4: cannot afford 200ms
            assertThat(budget.canAfford(Duration.ofMillis(200))).isFalse();
        }
    }

    @Test
    void noopBudgetNeverExhausts() {
        TimeoutBudget noop = TimeoutBudget.NOOP;

        noop.recordConsumption("ignored", Duration.ofMillis(500));
        assertThat(noop.segments()).isEmpty();
        assertThat(noop.canAfford(Duration.ofHours(1))).isTrue();
        assertThat(noop.isExpired()).isFalse();
    }
}
