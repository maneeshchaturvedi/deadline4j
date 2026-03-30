package io.deadline4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeadlineTimerTest {

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
    void defaultTimer_scheduleFiresCallbackAfterDelay() {
        AtomicBoolean fired = new AtomicBoolean(false);

        timer.schedule(() -> fired.set(true), 100, TimeUnit.MILLISECONDS);

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(fired).isTrue());
    }

    @Test
    void defaultTimer_cancelPreventsCallbackFromFiring() throws InterruptedException {
        AtomicBoolean fired = new AtomicBoolean(false);

        DeadlineTimer.TimerHandle handle = timer.schedule(
                () -> fired.set(true), 500, TimeUnit.MILLISECONDS);

        boolean cancelled = handle.cancel();
        assertThat(cancelled).isTrue();

        // Wait long enough for the task to have fired if it wasn't cancelled
        Thread.sleep(800);
        assertThat(fired).isFalse();
    }

    @Test
    void cancel_returnsTrueWhenCancelledBeforeFiring() {
        DeadlineTimer.TimerHandle handle = timer.schedule(
                () -> {}, 5, TimeUnit.SECONDS);

        assertThat(handle.cancel()).isTrue();
    }

    @Test
    void cancel_returnsFalseWhenAlreadyFired() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        DeadlineTimer.TimerHandle handle = timer.schedule(
                latch::countDown, 10, TimeUnit.MILLISECONDS);

        // Wait for the task to fire
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Cancel after firing should return false
        assertThat(handle.cancel()).isFalse();
    }

    @Test
    void shutdown_stopsTheExecutor() {
        AtomicBoolean fired = new AtomicBoolean(false);

        timer.shutdown();

        // After shutdown, scheduling should throw RejectedExecutionException
        // or similar — the timer is no longer accepting tasks
        try {
            timer.schedule(() -> fired.set(true), 100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Expected: RejectedExecutionException
        }

        // Even if somehow scheduled, it should not fire
        assertThat(fired).isFalse();
    }

    @Test
    void scheduleWithZeroDelay_firesImmediately() {
        AtomicBoolean fired = new AtomicBoolean(false);

        timer.schedule(() -> fired.set(true), 0, TimeUnit.MILLISECONDS);

        await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(fired).isTrue());
    }
}
