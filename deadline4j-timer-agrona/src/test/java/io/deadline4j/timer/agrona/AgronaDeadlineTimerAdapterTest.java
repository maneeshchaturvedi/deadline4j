package io.deadline4j.timer.agrona;

import io.deadline4j.DeadlineTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AgronaDeadlineTimerAdapterTest {

    private AgronaDeadlineTimerAdapter adapter;

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            try {
                adapter.shutdown();
            } catch (Exception ignored) {
                // timer may already be stopped
            }
        }
    }

    @Test
    void scheduleFires() {
        adapter = new AgronaDeadlineTimerAdapter();
        CountDownLatch latch = new CountDownLatch(1);

        adapter.schedule(latch::countDown, 50, TimeUnit.MILLISECONDS);

        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> assertThat(latch.getCount()).isZero());
    }

    @Test
    void cancelPrevents() throws InterruptedException {
        adapter = new AgronaDeadlineTimerAdapter();
        AtomicBoolean fired = new AtomicBoolean(false);

        DeadlineTimer.TimerHandle handle = adapter.schedule(
                () -> fired.set(true), 500, TimeUnit.MILLISECONDS);

        boolean cancelled = handle.cancel();
        assertThat(cancelled).isTrue();

        Thread.sleep(700);
        assertThat(fired.get()).isFalse();
    }

    @Test
    void shutdownStopsPolling() throws InterruptedException {
        adapter = new AgronaDeadlineTimerAdapter();
        adapter.shutdown();

        // Give the polling thread time to stop
        Thread.sleep(100);

        // The polling thread should no longer be alive after shutdown
        // Schedule should still technically work (adds to map) but nothing will fire
        AtomicBoolean fired = new AtomicBoolean(false);
        adapter.schedule(() -> fired.set(true), 10, TimeUnit.MILLISECONDS);
        Thread.sleep(200);
        assertThat(fired.get()).isFalse();

        adapter = null; // prevent double-shutdown in tearDown
    }

    @Test
    void multipleTimersFire() {
        adapter = new AgronaDeadlineTimerAdapter();
        CountDownLatch latch = new CountDownLatch(3);

        adapter.schedule(latch::countDown, 50, TimeUnit.MILLISECONDS);
        adapter.schedule(latch::countDown, 100, TimeUnit.MILLISECONDS);
        adapter.schedule(latch::countDown, 150, TimeUnit.MILLISECONDS);

        await().atMost(1, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(latch.getCount()).isZero());
    }
}
