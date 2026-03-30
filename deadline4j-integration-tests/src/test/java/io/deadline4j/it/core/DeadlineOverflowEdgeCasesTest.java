package io.deadline4j.it.core;

import io.deadline4j.CarrierGetter;
import io.deadline4j.Deadline;
import io.deadline4j.DeadlineCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests codec edge cases with invalid, boundary, and overflow inputs.
 */
class DeadlineOverflowEdgeCasesTest {

    private static final DeadlineCodec CODEC = DeadlineCodec.remainingMillis();
    private static final CarrierGetter<Map<String, String>> GETTER = Map::get;

    private Map<String, String> carrierWith(String remainingMs) {
        Map<String, String> carrier = new HashMap<>();
        if (remainingMs != null) {
            carrier.put("X-Deadline-Remaining-Ms", remainingMs);
        }
        return carrier;
    }

    @Test
    void zeroRemainingReturnsNull() {
        Deadline d = CODEC.extract(carrierWith("0"), GETTER);
        assertThat(d).isNull();
    }

    @Test
    void negativeRemainingReturnsNull() {
        Deadline d = CODEC.extract(carrierWith("-1"), GETTER);
        assertThat(d).isNull();
    }

    @Test
    void longMaxValueIsCapped() {
        Deadline d = CODEC.extract(carrierWith(String.valueOf(Long.MAX_VALUE)), GETTER);
        assertThat(d).isNotNull();
        // Capped at 5 minutes (300_000ms)
        assertThat(d.remainingMillis()).isLessThanOrEqualTo(300_000L);
        assertThat(d.remainingMillis()).isGreaterThan(0L);
    }

    @Test
    void nonNumericReturnsNull() {
        Deadline d = CODEC.extract(carrierWith("abc"), GETTER);
        assertThat(d).isNull();
    }

    @Test
    void emptyStringReturnsNull() {
        Deadline d = CODEC.extract(carrierWith(""), GETTER);
        assertThat(d).isNull();
    }

    @Test
    void nullHeaderReturnsNull() {
        Deadline d = CODEC.extract(carrierWith(null), GETTER);
        assertThat(d).isNull();
    }

    @Test
    void validRemainingProducesDeadline() {
        Deadline d = CODEC.extract(carrierWith("5000"), GETTER);
        assertThat(d).isNotNull();
        assertThat(d.remainingMillis()).isBetween(4900L, 5100L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "NaN", "Infinity", "-Infinity", "1e10"})
    void floatingPointAndSpecialValuesReturnNull(String value) {
        Deadline d = CODEC.extract(carrierWith(value), GETTER);
        assertThat(d).isNull();
    }
}
