package io.deadline4j.timer.netty;

import io.deadline4j.DeadlineTimer;
import io.netty.util.HashedWheelTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NettyHashedWheelTimerAdapterTest {

    private NettyHashedWheelTimerAdapter adapter;

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
        adapter = new NettyHashedWheelTimerAdapter();
        CountDownLatch latch = new CountDownLatch(1);

        adapter.schedule(latch::countDown, 100, TimeUnit.MILLISECONDS);

        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> assertThat(latch.getCount()).isZero());
    }

    @Test
    void cancelPrevents() throws InterruptedException {
        adapter = new NettyHashedWheelTimerAdapter();
        AtomicBoolean fired = new AtomicBoolean(false);

        DeadlineTimer.TimerHandle handle = adapter.schedule(
                () -> fired.set(true), 500, TimeUnit.MILLISECONDS);

        boolean cancelled = handle.cancel();
        assertThat(cancelled).isTrue();

        Thread.sleep(700);
        assertThat(fired.get()).isFalse();
    }

    @Test
    void shutdownStopsTimer() {
        adapter = new NettyHashedWheelTimerAdapter();
        adapter.shutdown();

        // After shutdown, scheduling should throw
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> adapter.schedule(() -> {}, 100, TimeUnit.MILLISECONDS));
        adapter = null; // prevent double-shutdown in tearDown
    }

    @Test
    void defaultConstructorCreatesTimer() {
        adapter = new NettyHashedWheelTimerAdapter();
        CountDownLatch latch = new CountDownLatch(1);

        adapter.schedule(latch::countDown, 50, TimeUnit.MILLISECONDS);

        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> assertThat(latch.getCount()).isZero());
    }

    @Test
    void customTimerConstructor() {
        HashedWheelTimer custom = new HashedWheelTimer(
                r -> {
                    Thread t = new Thread(r, "custom-netty-timer");
                    t.setDaemon(true);
                    return t;
                },
                50, TimeUnit.MILLISECONDS, 256);

        adapter = new NettyHashedWheelTimerAdapter(custom);
        CountDownLatch latch = new CountDownLatch(1);

        adapter.schedule(latch::countDown, 50, TimeUnit.MILLISECONDS);

        await().atMost(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> assertThat(latch.getCount()).isZero());
    }
}
