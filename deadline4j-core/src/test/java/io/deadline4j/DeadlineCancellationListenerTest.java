package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeadlineCancellationListenerTest {

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
    void onExpiration_firesAfterDeadlineExpires() {
        Deadline deadline = Deadline.after(Duration.ofMillis(100));
        AtomicBoolean fired = new AtomicBoolean(false);

        deadline.onExpiration(() -> fired.set(true), timer);

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(fired).isTrue());
    }

    @Test
    void onExpiration_firesImmediatelyIfAlreadyExpired() {
        // Create already-expired deadline
        Deadline deadline = Deadline.after(Duration.ZERO);
        // Small sleep to ensure it's truly expired
        AtomicBoolean fired = new AtomicBoolean(false);

        deadline.onExpiration(() -> fired.set(true), timer);

        // Should have fired synchronously
        assertThat(fired.get()).isTrue();
    }

    @Test
    void cancel_preventsListenerFromFiring() throws InterruptedException {
        Deadline deadline = Deadline.after(Duration.ofMillis(500));
        AtomicBoolean fired = new AtomicBoolean(false);

        DeadlineTimer.TimerHandle handle = deadline.onExpiration(() -> fired.set(true), timer);
        boolean cancelled = handle.cancel();

        assertThat(cancelled).isTrue();

        // Wait past the deadline
        Thread.sleep(700);
        assertThat(fired.get()).isFalse();
    }

    @Test
    void listenerIsNotCalledAfterCancel() throws InterruptedException {
        Deadline deadline = Deadline.after(Duration.ofMillis(200));
        AtomicBoolean fired = new AtomicBoolean(false);

        DeadlineTimer.TimerHandle handle = deadline.onExpiration(() -> fired.set(true), timer);
        handle.cancel();

        // Wait well past the deadline
        Thread.sleep(400);
        assertThat(fired.get()).isFalse();
    }

    @Test
    void returnedHandle_cancelReturnsFalseIfAlreadyFired() {
        Deadline deadline = Deadline.after(Duration.ZERO);
        AtomicBoolean fired = new AtomicBoolean(false);

        DeadlineTimer.TimerHandle handle = deadline.onExpiration(() -> fired.set(true), timer);

        // Already fired synchronously
        assertThat(fired.get()).isTrue();
        assertThat(handle.cancel()).isFalse();
    }
}
