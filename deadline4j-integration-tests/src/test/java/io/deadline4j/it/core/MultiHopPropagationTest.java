package io.deadline4j.it.core;

import io.deadline4j.CarrierGetter;
import io.deadline4j.CarrierSetter;
import io.deadline4j.Deadline;
import io.deadline4j.DeadlineCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates A -> B -> C deadline propagation using codec encode/decode.
 */
class MultiHopPropagationTest {

    private static final CarrierSetter<Map<String, String>> SETTER = Map::put;
    private static final CarrierGetter<Map<String, String>> GETTER = Map::get;

    @Test
    void deadlinePropagatesThroughThreeHops() throws InterruptedException {
        DeadlineCodec codec = DeadlineCodec.remainingMillis();
        String deadlineId = "req-multi-hop-001";

        // Service A: create 5000ms deadline
        Deadline deadlineA = Deadline.after(Duration.ofMillis(5000), deadlineId);

        // Simulate 500ms processing in service A before encoding
        Thread.sleep(500);

        // Service A encodes to headers (outbound to B)
        Map<String, String> headersAtoB = new HashMap<>();
        codec.inject(deadlineA, headersAtoB, SETTER);

        // Service B: decode from headers (near-instant network in-process)
        Deadline deadlineB = codec.extract(headersAtoB, GETTER);
        assertThat(deadlineB).isNotNull();
        long remainingAtB = deadlineB.remainingMillis();
        // After 500ms elapsed, ~4500ms remaining (with tolerance)
        assertThat(remainingAtB).isBetween(4200L, 4700L);

        // Verify ID propagated
        assertThat(deadlineB.id()).isPresent().hasValue(deadlineId);

        // Simulate 300ms processing in service B before encoding
        Thread.sleep(300);

        // Service B re-encodes (outbound to C)
        Map<String, String> headersBtoC = new HashMap<>();
        codec.inject(deadlineB, headersBtoC, SETTER);

        // Service C: decode
        Deadline deadlineC = codec.extract(headersBtoC, GETTER);
        assertThat(deadlineC).isNotNull();
        long remainingAtC = deadlineC.remainingMillis();
        // After 800ms total elapsed, ~4200ms remaining (with tolerance)
        assertThat(remainingAtC).isBetween(3800L, 4400L);

        // Verify ID still propagated
        assertThat(deadlineC.id()).isPresent().hasValue(deadlineId);

        // Verify monotonic decrease
        assertThat(remainingAtC).isLessThan(remainingAtB);
    }

    @Test
    void deadlineWithoutIdPropagates() throws InterruptedException {
        DeadlineCodec codec = DeadlineCodec.remainingMillis();

        Deadline deadline = Deadline.after(Duration.ofMillis(3000));
        Map<String, String> headers = new HashMap<>();
        codec.inject(deadline, headers, SETTER);

        Thread.sleep(100);

        Deadline decoded = codec.extract(headers, GETTER);
        assertThat(decoded).isNotNull();
        assertThat(decoded.id()).isEmpty();
        assertThat(decoded.remainingMillis()).isBetween(2500L, 3000L);
    }
}
