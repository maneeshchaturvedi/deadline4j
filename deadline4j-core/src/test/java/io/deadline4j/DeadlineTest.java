package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DeadlineTest {

    // --- Creation via after() ---

    @Test
    void afterDuration_createsNonExpiredDeadline() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThat(deadline.isExpired()).isFalse();
        assertThat(deadline.remainingMillis()).isGreaterThan(0);
    }

    @Test
    void afterDuration_withId() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "req-123");
        assertThat(deadline.id()).isPresent().contains("req-123");
    }

    @Test
    void afterDuration_withoutId() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThat(deadline.id()).isEmpty();
    }

    @Test
    void afterDuration_nullDurationThrowsNPE() {
        assertThatNullPointerException().isThrownBy(() -> Deadline.after(null));
    }

    @Test
    void afterDuration_zeroDuration_expiresImmediately() {
        Deadline deadline = Deadline.after(Duration.ZERO);
        assertThat(deadline.isExpired()).isTrue();
        assertThat(deadline.remainingMillis()).isEqualTo(0);
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void afterDuration_negativeDuration_expiresImmediately() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(-10));
        assertThat(deadline.isExpired()).isTrue();
        assertThat(deadline.remainingMillis()).isEqualTo(0);
    }

    @Test
    void afterDuration_largeDuration_notCapped() {
        // Application code should be able to set deadlines longer than 5 minutes.
        // Only wire-received values (fromRemainingMillis) are capped.
        Deadline deadline = Deadline.after(Duration.ofHours(1));
        assertThat(deadline.remainingMillis()).isGreaterThan(300_000L);
        assertThat(deadline.isExpired()).isFalse();
    }

    // --- Creation via fromRemainingMillis() ---

    @Test
    void fromRemainingMillis_createsDeadline() {
        Deadline deadline = Deadline.fromRemainingMillis(5000);
        assertThat(deadline.isExpired()).isFalse();
        assertThat(deadline.remainingMillis()).isGreaterThan(0).isLessThanOrEqualTo(5000);
    }

    @Test
    void fromRemainingMillis_withId() {
        Deadline deadline = Deadline.fromRemainingMillis(1000, "trace-456");
        assertThat(deadline.id()).isPresent().contains("trace-456");
    }

    @Test
    void fromRemainingMillis_withoutId() {
        Deadline deadline = Deadline.fromRemainingMillis(1000);
        assertThat(deadline.id()).isEmpty();
    }

    @Test
    void fromRemainingMillis_zeroMillis_expiresImmediately() {
        Deadline deadline = Deadline.fromRemainingMillis(0);
        assertThat(deadline.isExpired()).isTrue();
        assertThat(deadline.remainingMillis()).isEqualTo(0);
    }

    @Test
    void fromRemainingMillis_negativeMillis_treatedAsZero() {
        Deadline deadline = Deadline.fromRemainingMillis(-500);
        assertThat(deadline.isExpired()).isTrue();
        assertThat(deadline.remainingMillis()).isEqualTo(0);
    }

    @Test
    void fromRemainingMillis_longMaxValue_doesNotOverflow() {
        // This is the critical overflow fix: Long.MAX_VALUE millis should
        // be capped to MAX_REMAINING_MS (300,000ms = 5 minutes)
        Deadline deadline = Deadline.fromRemainingMillis(Long.MAX_VALUE);
        assertThat(deadline.isExpired()).isFalse();
        // Should be capped at ~300,000ms, not overflow to negative
        assertThat(deadline.remainingMillis()).isGreaterThan(0).isLessThanOrEqualTo(300_000L);
    }

    @Test
    void fromRemainingMillis_veryLargeValue_cappedToMax() {
        Deadline deadline = Deadline.fromRemainingMillis(600_000);
        // 600,000ms (10 min) should be capped to 300,000ms (5 min)
        assertThat(deadline.remainingMillis()).isLessThanOrEqualTo(300_000L);
    }

    // --- remaining() ---

    @Test
    void remaining_returnsPositiveDuration_whenNotExpired() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));
        Duration remaining = deadline.remaining();
        assertThat(remaining).isPositive();
        assertThat(remaining).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void remaining_returnsZero_whenExpired() {
        Deadline deadline = Deadline.after(Duration.ZERO);
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void remaining_neverNegative() {
        // Create an already-expired deadline
        Deadline deadline = Deadline.fromRemainingMillis(-1000);
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
        assertThat(deadline.remaining().isNegative()).isFalse();
    }

    // --- remainingMillis() ---

    @Test
    void remainingMillis_returnsPositive_whenNotExpired() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThat(deadline.remainingMillis()).isGreaterThan(0);
    }

    @Test
    void remainingMillis_returnsZero_whenExpired() {
        Deadline deadline = Deadline.after(Duration.ZERO);
        assertThat(deadline.remainingMillis()).isEqualTo(0);
    }

    // --- isExpired() ---

    @Test
    void isExpired_false_whenTimeRemaining() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(10));
        assertThat(deadline.isExpired()).isFalse();
    }

    @Test
    void isExpired_true_whenExpired() {
        Deadline deadline = Deadline.after(Duration.ZERO);
        assertThat(deadline.isExpired()).isTrue();
    }

    // --- min() ---

    @Test
    void min_returnsEarlierDeadline() {
        Deadline sooner = Deadline.after(Duration.ofSeconds(1));
        Deadline later = Deadline.after(Duration.ofSeconds(10));
        assertThat(sooner.min(later)).isSameAs(sooner);
        assertThat(later.min(sooner)).isSameAs(sooner);
    }

    @Test
    void min_returnsSelf_whenEqual() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThat(deadline.min(deadline)).isSameAs(deadline);
    }

    @Test
    void min_nullOtherThrowsNPE() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThatNullPointerException().isThrownBy(() -> deadline.min(null));
    }

    // --- compareTo() ---

    @Test
    void compareTo_earlierIsNegative() {
        Deadline sooner = Deadline.after(Duration.ofSeconds(1));
        Deadline later = Deadline.after(Duration.ofSeconds(10));
        assertThat(sooner.compareTo(later)).isNegative();
    }

    @Test
    void compareTo_laterIsPositive() {
        Deadline sooner = Deadline.after(Duration.ofSeconds(1));
        Deadline later = Deadline.after(Duration.ofSeconds(10));
        assertThat(later.compareTo(sooner)).isPositive();
    }

    @Test
    void compareTo_sameIsZero() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThat(deadline.compareTo(deadline)).isZero();
    }

    // --- toString() ---

    @Test
    void toString_showsRemainingMillis() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        String str = deadline.toString();
        assertThat(str).startsWith("Deadline[remaining=");
        assertThat(str).endsWith("ms]");
        assertThat(str).doesNotContain("id=");
    }

    @Test
    void toString_showsId_whenPresent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "my-id");
        String str = deadline.toString();
        assertThat(str).contains("id=my-id");
    }

    @Test
    void toString_omitsId_whenNull() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        assertThat(deadline.toString()).doesNotContain("id=");
    }

    // --- id() ---

    @Test
    void id_returnsOptionalWithId() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(1), "abc");
        assertThat(deadline.id()).isPresent().contains("abc");
    }

    @Test
    void id_returnsEmpty_whenNoId() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(1));
        assertThat(deadline.id()).isEmpty();
    }
}
