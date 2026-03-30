package io.deadline4j.it.core;

import io.deadline4j.Deadline;
import io.deadline4j.DeadlineTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests expiration listener registration and cancellation.
 */
class CancellationListenerChainTest {

    private DeadlineTimer timer;

    @BeforeEach
    void setUp() {
        timer = DeadlineTimer.defaultTimer();
    }

    @AfterEach
    void tearDown() {
        timer.shutdown();
    }

    @Test
    void cancelledListenerDoesNotFire() {
        Deadline deadline = Deadline.after(Duration.ofMillis(200));

        AtomicBoolean listener1Fired = new AtomicBoolean(false);
        AtomicBoolean listener2Fired = new AtomicBoolean(false);
        AtomicBoolean listener3Fired = new AtomicBoolean(false);

        // Register 3 listeners
        deadline.onExpiration(() -> listener1Fired.set(true), timer);
        DeadlineTimer.TimerHandle handle2 =
                deadline.onExpiration(() -> listener2Fired.set(true), timer);
        deadline.onExpiration(() -> listener3Fired.set(true), timer);

        // Cancel the 2nd one immediately
        boolean cancelled = handle2.cancel();
        assertThat(cancelled).isTrue();

        // Wait for deadline to expire
        await().atMost(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(listener1Fired.get()).isTrue();
                    assertThat(listener3Fired.get()).isTrue();
                });

        // Listener 2 should NOT have fired
        assertThat(listener2Fired.get()).isFalse();
    }

    @Test
    void alreadyExpiredDeadlineFiresListenerImmediately() {
        // Create an already-expired deadline
        Deadline expired = Deadline.after(Duration.ZERO);

        AtomicBoolean fired = new AtomicBoolean(false);
        expired.onExpiration(() -> fired.set(true), timer);

        // Should fire immediately (synchronously)
        assertThat(fired.get()).isTrue();
    }

    @Test
    void cancelAfterFiringReturnsFalse() throws InterruptedException {
        Deadline deadline = Deadline.after(Duration.ofMillis(50));

        AtomicBoolean fired = new AtomicBoolean(false);
        DeadlineTimer.TimerHandle handle =
                deadline.onExpiration(() -> fired.set(true), timer);

        // Wait for it to fire
        await().atMost(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(fired.get()).isTrue());

        // Cancelling after firing should return false
        boolean cancelled = handle.cancel();
        assertThat(cancelled).isFalse();
    }
}
