package io.deadline4j.timer.agrona;

import io.deadline4j.DeadlineTimer;
import org.agrona.DeadlineTimerWheel;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapts Agrona's {@link DeadlineTimerWheel} to the {@link DeadlineTimer} SPI.
 * Ultra-low-latency timer with polling-based expiry.
 */
public class AgronaDeadlineTimerAdapter implements DeadlineTimer {

    private final DeadlineTimerWheel wheel;
    private final Long2ObjectHashMap<Runnable> tasks = new Long2ObjectHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread pollingThread;

    /** Create with default settings (~1ms tick resolution, 1024 ticks per wheel). */
    public AgronaDeadlineTimerAdapter() {
        // Agrona requires tick resolution to be a power of 2.
        // 2^20 = 1,048,576 ns ≈ 1.05ms
        this(TimeUnit.NANOSECONDS, 1L << 20, 1024);
    }

    /**
     * @param timeUnit        the time unit for the wheel
     * @param tickResolution  tick resolution in the given time unit
     * @param ticksPerWheel   number of ticks per wheel revolution
     */
    public AgronaDeadlineTimerAdapter(TimeUnit timeUnit, long tickResolution, int ticksPerWheel) {
        this.wheel = new DeadlineTimerWheel(timeUnit, System.nanoTime(), tickResolution, ticksPerWheel);
        this.pollingThread = new Thread(this::pollLoop, "deadline4j-agrona-timer");
        this.pollingThread.setDaemon(true);
        this.pollingThread.start();
    }

    @Override
    public TimerHandle schedule(Runnable task, long delay, TimeUnit unit) {
        long deadlineNs = System.nanoTime() + unit.toNanos(delay);
        long timerId;
        synchronized (this) {
            timerId = wheel.scheduleTimer(deadlineNs);
            tasks.put(timerId, task);
        }
        long id = timerId;
        return () -> {
            synchronized (AgronaDeadlineTimerAdapter.this) {
                Runnable removed = tasks.remove(id);
                if (removed != null) {
                    wheel.cancelTimer(id);
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public void shutdown() {
        running.set(false);
        pollingThread.interrupt();
    }

    private void pollLoop() {
        while (running.get()) {
            // Collect expired tasks inside the lock, run them outside
            // to avoid deadlock if a callback calls schedule() or cancel().
            List<Runnable> expired = new ArrayList<>();
            synchronized (this) {
                wheel.poll(System.nanoTime(), (timeUnit, now, timerId) -> {
                    Runnable task = tasks.remove(timerId);
                    if (task != null) {
                        expired.add(task);
                    }
                    return true;
                }, Integer.MAX_VALUE);
            }
            for (Runnable task : expired) {
                task.run();
            }
            try {
                Thread.sleep(1); // 1ms polling interval
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
