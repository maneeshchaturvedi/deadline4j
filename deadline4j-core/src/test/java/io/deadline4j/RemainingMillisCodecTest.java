package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemainingMillisCodecTest {

    private final DeadlineCodec codec = DeadlineCodec.remainingMillis();

    private final CarrierSetter<Map<String, String>> setter = Map::put;
    private final CarrierGetter<Map<String, String>> getter = Map::get;

    // --- Factory method ---

    @Test
    void factoryMethod_returnsCodecInstance() {
        DeadlineCodec codec = DeadlineCodec.remainingMillis();
        assertThat(codec).isNotNull();
        assertThat(codec).isInstanceOf(RemainingMillisCodec.class);
    }

    // --- inject() ---

    @Test
    void inject_writesRemainingMillisHeader() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        Map<String, String> carrier = new HashMap<>();

        codec.inject(deadline, carrier, setter);

        assertThat(carrier).containsKey("X-Deadline-Remaining-Ms");
        long value = Long.parseLong(carrier.get("X-Deadline-Remaining-Ms"));
        assertThat(value).isGreaterThan(0).isLessThanOrEqualTo(5000);
    }

    @Test
    void inject_writesIdHeader_whenPresent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), "req-abc-123");
        Map<String, String> carrier = new HashMap<>();

        codec.inject(deadline, carrier, setter);

        assertThat(carrier).containsEntry("X-Deadline-Id", "req-abc-123");
    }

    @Test
    void inject_doesNotWriteIdHeader_whenAbsent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(5));
        Map<String, String> carrier = new HashMap<>();

        codec.inject(deadline, carrier, setter);

        assertThat(carrier).doesNotContainKey("X-Deadline-Id");
    }

    // --- extract() ---

    @Test
    void extract_withMissingHeader_returnsNull() {
        Map<String, String> carrier = new HashMap<>();

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNull();
    }

    @Test
    void extract_withEmptyHeader_returnsNull() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNull();
    }

    @Test
    void extract_withInvalidNumber_returnsNull() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "not-a-number");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNull();
    }

    @Test
    void extract_withZeroValue_returnsNull() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "0");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNull();
    }

    @Test
    void extract_withNegativeValue_returnsNull() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "-100");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNull();
    }

    @Test
    void extract_withValidValue_createsDeadline() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "5000");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNotNull();
        assertThat(result.isExpired()).isFalse();
        assertThat(result.remainingMillis()).isGreaterThan(0).isLessThanOrEqualTo(5000);
    }

    @Test
    void extract_withId_populatesDeadlineId() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "5000");
        carrier.put("X-Deadline-Id", "txn-xyz-789");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNotNull();
        assertThat(result.id()).isPresent().contains("txn-xyz-789");
    }

    @Test
    void extract_withoutId_deadlineIdIsEmpty() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", "5000");

        Deadline result = codec.extract(carrier, getter);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEmpty();
    }

    @Test
    void extract_withLongMaxValue_handledByOverflowProtection() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Deadline-Remaining-Ms", String.valueOf(Long.MAX_VALUE));

        Deadline result = codec.extract(carrier, getter);

        // Deadline.fromRemainingMillis caps at MAX_REMAINING_MS (5 minutes)
        assertThat(result).isNotNull();
        assertThat(result.isExpired()).isFalse();
        assertThat(result.remainingMillis()).isLessThanOrEqualTo(300_000);
    }

    // --- Round-trip ---

    @Test
    void injectExtract_roundTrip_preservesDeadlineApproximately() {
        Deadline original = Deadline.after(Duration.ofSeconds(3), "round-trip-id");
        Map<String, String> carrier = new HashMap<>();

        codec.inject(original, carrier, setter);
        Deadline restored = codec.extract(carrier, getter);

        assertThat(restored).isNotNull();
        assertThat(restored.isExpired()).isFalse();
        // Allow some tolerance for time elapsed during inject/extract
        assertThat(restored.remainingMillis()).isGreaterThan(2000).isLessThanOrEqualTo(3000);
        assertThat(restored.id()).isPresent().contains("round-trip-id");
    }

    @Test
    void injectExtract_roundTrip_withoutId() {
        Deadline original = Deadline.after(Duration.ofSeconds(2));
        Map<String, String> carrier = new HashMap<>();

        codec.inject(original, carrier, setter);
        Deadline restored = codec.extract(carrier, getter);

        assertThat(restored).isNotNull();
        assertThat(restored.remainingMillis()).isGreaterThan(0).isLessThanOrEqualTo(2000);
        assertThat(restored.id()).isEmpty();
    }
}
