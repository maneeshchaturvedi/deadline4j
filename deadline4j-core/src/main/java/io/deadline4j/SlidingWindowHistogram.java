package io.deadline4j;

/**
 * Two-phase sliding window over HdrHistogram. Not public API.
 *
 * <p>Maintains two HdrHistogram instances, rotated at windowSize/2
 * intervals. Queries merge both, providing a rolling view covering
 * at least windowSize/2 of recent data without per-second bucket cost.
 *
 * <p><strong>Thread-safety:</strong> The hot path ({@code recordValue}
 * when no rotation is needed) avoids the lock entirely — it reads the
 * volatile {@code pair} and records into the current histogram (which
 * uses internal CAS). However, at rotation boundaries (at most twice
 * per window), {@code maybeRotate()} acquires {@code rotateLock}, and
 * all concurrent threads calling {@code recordValue} or
 * {@code getValueAtPercentile} at that instant will contend on it.
 * Therefore, {@code recordValue} is <em>not</em> lock-free in the
 * strict sense — it is lock-free on the common path but synchronized
 * at rotation boundaries.
 */
final class SlidingWindowHistogram {

    private volatile HistogramPair pair;
    private volatile long rotationDeadlineNanos;
    private final long halfWindowNanos;
    private final long highestTrackableValue;
    private final Object rotateLock = new Object();

    /** Minimum floor for highestTrackableValue: 10 minutes in ms. */
    private static final long HIGHEST_TRACKABLE_FLOOR = 600_000L;

    /** Immutable pair of references. Published atomically via volatile. */
    private static final class HistogramPair {
        final org.HdrHistogram.Histogram current;
        final org.HdrHistogram.Histogram previous;

        HistogramPair(org.HdrHistogram.Histogram current,
                      org.HdrHistogram.Histogram previous) {
            this.current = current;
            this.previous = previous;
        }
    }

    SlidingWindowHistogram(long windowNanos, long configHighestTrackableValue) {
        this.halfWindowNanos = windowNanos / 2;
        this.highestTrackableValue = Math.max(configHighestTrackableValue, HIGHEST_TRACKABLE_FLOOR);
        this.pair = new HistogramPair(
                new org.HdrHistogram.Histogram(this.highestTrackableValue, 2),
                new org.HdrHistogram.Histogram(this.highestTrackableValue, 2));
        this.rotationDeadlineNanos = System.nanoTime() + halfWindowNanos;
    }

    void recordValue(long value) {
        maybeRotate();
        HistogramPair snap = pair; // single volatile read AFTER rotation
        snap.current.recordValue(Math.min(value, highestTrackableValue));
    }

    long getValueAtPercentile(double percentile) {
        maybeRotate();
        HistogramPair snap = pair; // single volatile read
        org.HdrHistogram.Histogram merged = snap.current.copy();
        merged.add(snap.previous);
        return merged.getValueAtPercentile(percentile);
    }

    long totalCount() {
        HistogramPair snap = pair;
        return snap.current.getTotalCount() + snap.previous.getTotalCount();
    }

    long highestTrackableValue() {
        return highestTrackableValue;
    }

    private void maybeRotate() {
        if (System.nanoTime() < rotationDeadlineNanos) return;
        synchronized (rotateLock) {
            if (System.nanoTime() < rotationDeadlineNanos) return;
            HistogramPair old = pair;
            // Old previous becomes new current (after reset).
            // Old current becomes new previous (retains recent data).
            old.previous.reset();
            pair = new HistogramPair(old.previous, old.current);
            rotationDeadlineNanos = System.nanoTime() + halfWindowNanos;
        }
    }
}
