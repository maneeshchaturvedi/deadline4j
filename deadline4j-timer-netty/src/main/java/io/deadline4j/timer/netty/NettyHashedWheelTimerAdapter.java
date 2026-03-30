package io.deadline4j.timer.netty;

import io.deadline4j.DeadlineTimer;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * Adapts Netty's {@link HashedWheelTimer} to the {@link DeadlineTimer} SPI.
 * O(1) scheduling, suitable for 100K+ concurrent deadlines.
 */
public class NettyHashedWheelTimerAdapter implements DeadlineTimer {

    private final HashedWheelTimer timer;

    /** Create with default settings (100ms tick, 512 slots, daemon thread). */
    public NettyHashedWheelTimerAdapter() {
        this(new HashedWheelTimer(
            r -> {
                Thread t = new Thread(r, "deadline4j-netty-timer");
                t.setDaemon(true);
                return t;
            },
            100, TimeUnit.MILLISECONDS, 512));
    }

    /** Create wrapping an existing HashedWheelTimer. */
    public NettyHashedWheelTimerAdapter(HashedWheelTimer timer) {
        this.timer = timer;
        this.timer.start();
    }

    @Override
    public TimerHandle schedule(Runnable task, long delay, TimeUnit unit) {
        Timeout timeout = timer.newTimeout(t -> task.run(), delay, unit);
        return () -> timeout.cancel();
    }

    @Override
    public void shutdown() {
        timer.stop();
    }
}
