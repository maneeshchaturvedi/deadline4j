package io.deadline4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction over timer scheduling for deadline expiration callbacks.
 *
 * <p>The default uses a single-thread ScheduledExecutorService.
 * For 100K+ concurrent deadlines, swap in Netty's HashedWheelTimer
 * (via deadline4j-timer-netty) or Agrona's DeadlineTimerWheel
 * (via deadline4j-timer-agrona).
 */
public interface DeadlineTimer {

    /**
     * Schedule a task to run after the specified delay.
     *
     * @param task  the task to execute
     * @param delay the delay before execution
     * @param unit  the time unit of the delay
     * @return a handle that can be used to cancel the scheduled task
     */
    TimerHandle schedule(Runnable task, long delay, TimeUnit unit);

    /**
     * Shut down this timer, releasing any resources.
     */
    void shutdown();

    /**
     * A handle to a scheduled timer task that supports cancellation.
     */
    interface TimerHandle {
        /**
         * Attempt to cancel the scheduled task.
         *
         * @return {@code true} if the task was successfully cancelled
         *         before it fired; {@code false} if it already fired
         *         or was already cancelled
         */
        boolean cancel();
    }

    /**
     * Default timer: ScheduledExecutorService with 1 daemon thread
     * named "deadline4j-timer".
     */
    static DeadlineTimer defaultTimer() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deadline4j-timer");
            t.setDaemon(true);
            return t;
        });
        return new DeadlineTimer() {
            @Override
            public TimerHandle schedule(Runnable task, long delay, TimeUnit unit) {
                ScheduledFuture<?> future = executor.schedule(task, delay, unit);
                return () -> future.cancel(false);
            }

            @Override
            public void shutdown() {
                executor.shutdown();
            }
        };
    }
}
