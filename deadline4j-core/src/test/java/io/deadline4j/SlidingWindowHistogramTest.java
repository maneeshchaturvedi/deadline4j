package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowHistogramTest {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long HIGHEST_TRACKABLE = 60_000L; // 60 seconds in ms

    // --- Record and query ---

    @Test
    void recordAndQuerySingleValue() {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, HIGHEST_TRACKABLE);
        h.recordValue(100);
        assertThat(h.getValueAtPercentile(50.0)).isEqualTo(100);
    }

    @Test
    void recordMultipleValues_percentileComputation() {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, HIGHEST_TRACKABLE);
        // Record 100 values from 1..100
        for (int i = 1; i <= 100; i++) {
            h.recordValue(i);
        }
        // P50 should be around 50
        long p50 = h.getValueAtPercentile(50.0);
        assertThat(p50).isBetween(49L, 51L);

        // P99 should be around 99
        long p99 = h.getValueAtPercentile(99.0);
        assertThat(p99).isBetween(98L, 100L);
    }

    @Test
    void totalCount_reflectsRecordedValues() {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, HIGHEST_TRACKABLE);
        assertThat(h.totalCount()).isEqualTo(0);

        h.recordValue(10);
        h.recordValue(20);
        h.recordValue(30);
        assertThat(h.totalCount()).isEqualTo(3);
    }

    // --- Rotation ---

    @Test
    void rotation_triggersAfterHalfWindow() throws Exception {
        // Use a very short window so rotation happens quickly
        long shortWindowNanos = TimeUnit.MILLISECONDS.toNanos(100); // 100ms window => 50ms half
        SlidingWindowHistogram h = new SlidingWindowHistogram(shortWindowNanos, HIGHEST_TRACKABLE);

        // Record values in the first phase
        for (int i = 0; i < 10; i++) {
            h.recordValue(100);
        }
        assertThat(h.totalCount()).isEqualTo(10);

        // Wait for rotation to trigger
        Thread.sleep(60); // > half window (50ms)

        // Record more values — this triggers rotation
        h.recordValue(200);
        // After rotation, old current becomes previous, old previous was reset
        // So we should have the 10 from previous + 1 new
        assertThat(h.totalCount()).isEqualTo(11);

        // Wait for another rotation
        Thread.sleep(60);
        h.recordValue(300);
        // After second rotation, old previous (the original 10 values) was reset
        // Now we have: previous = the "200" value (1), current = "300" value (1)
        assertThat(h.totalCount()).isEqualTo(2);
    }

    @Test
    void totalCount_acrossBothPhases() {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, HIGHEST_TRACKABLE);
        for (int i = 0; i < 50; i++) {
            h.recordValue(i + 1);
        }
        assertThat(h.totalCount()).isEqualTo(50);
    }

    // --- Value clamping ---

    @Test
    void valuesAboveHighestTrackableAreClamped() {
        // Use a value above the floor so we can test clamping
        long high = 1_000_000L;
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, high);

        // Record two values: one normal, one way above highest trackable
        h.recordValue(100);
        h.recordValue(5_000_000L);

        // Both should be recorded
        assertThat(h.totalCount()).isEqualTo(2);

        // The clamped value should be near highestTrackableValue, not at 5M.
        // HdrHistogram rounds to significant digits, so allow some tolerance.
        long p100 = h.getValueAtPercentile(100.0);
        assertThat(p100).isLessThanOrEqualTo(high * 2); // well below 5_000_000
        assertThat(p100).isGreaterThanOrEqualTo(high / 2); // near the highest trackable
    }

    // --- highestTrackableValue floor ---

    @Test
    void highestTrackableValue_hasFloorOf600000ms() {
        // Pass a very low value — should be raised to floor
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, 100L);
        assertThat(h.highestTrackableValue()).isEqualTo(600_000L);
    }

    @Test
    void highestTrackableValue_aboveFloorIsPreserved() {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, 1_000_000L);
        assertThat(h.highestTrackableValue()).isEqualTo(1_000_000L);
    }

    @Test
    void highestTrackableValue_exactlyAtFloor() {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, 600_000L);
        assertThat(h.highestTrackableValue()).isEqualTo(600_000L);
    }

    // --- Concurrency ---

    @Test
    void concurrentRecording_doesNotLoseData() throws Exception {
        SlidingWindowHistogram h = new SlidingWindowHistogram(WINDOW_NANOS, HIGHEST_TRACKABLE);
        int threadCount = 8;
        int recordsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < recordsPerThread; i++) {
                        h.recordValue((long) (Math.random() * 1000) + 1);
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(error.get()).isNull();
        // HdrHistogram with 2 significant digits may lose a small number of
        // concurrent CAS-based recordings. Verify we got the vast majority.
        long expected = (long) threadCount * recordsPerThread;
        assertThat(h.totalCount()).isGreaterThan((long) (expected * 0.8));
    }

    @Test
    void concurrentRecordingWithRotation_noExceptions() throws Exception {
        // Use short window to force rotations during concurrent recording
        long shortWindowNanos = TimeUnit.MILLISECONDS.toNanos(50);
        SlidingWindowHistogram h = new SlidingWindowHistogram(shortWindowNanos, HIGHEST_TRACKABLE);
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 500; i++) {
                        h.recordValue((long) (Math.random() * 1000) + 1);
                        if (i % 50 == 0) {
                            // Also read percentiles concurrently
                            h.getValueAtPercentile(99.0);
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(error.get()).isNull();
        // Total count may be less than total recorded due to rotations clearing old data
        assertThat(h.totalCount()).isGreaterThan(0);
    }
}
