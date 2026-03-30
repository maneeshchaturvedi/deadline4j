package io.deadline4j.spring.openfeign;

import io.deadline4j.DeadlineExceededException;
import io.deadline4j.OptionalCallSkippedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class Deadline4jFallbackFactoryTest {

    interface TestClient {
        String getName();
        List<String> getItems();
        Optional<String> findItem();
        int getCount();
        boolean isActive();
        void doAction();
    }

    private final Deadline4jFallbackFactory<TestClient> factory =
        new Deadline4jFallbackFactory<>(TestClient.class);

    @Test
    void deadlineExceeded_returnsProxy() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThat(proxy).isNotNull();
        assertThat(proxy).isInstanceOf(TestClient.class);
    }

    @Test
    void proxy_returnsNull_forString() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThat(proxy.getName()).isNull();
    }

    @Test
    void proxy_returnsEmptyList() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThat(proxy.getItems()).isEmpty();
    }

    @Test
    void proxy_returnsOptionalEmpty() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThat(proxy.findItem()).isEmpty();
    }

    @Test
    void proxy_returnsZero_forInt() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThat(proxy.getCount()).isEqualTo(0);
    }

    @Test
    void proxy_returnsFalse_forBoolean() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThat(proxy.isActive()).isFalse();
    }

    @Test
    void proxy_returnsNull_forVoid() {
        TestClient proxy = factory.create(new DeadlineExceededException("timeout"));
        assertThatCode(proxy::doAction).doesNotThrowAnyException();
    }

    @Test
    void optionalCallSkipped_returnsProxy() {
        TestClient proxy = factory.create(new OptionalCallSkippedException("svc", 100));
        assertThat(proxy).isNotNull();
        assertThat(proxy.getItems()).isEmpty();
    }

    @Test
    void otherRuntimeException_rethrown() {
        assertThatThrownBy(() -> factory.create(new IllegalStateException("bad")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("bad");
    }

    @Test
    void checkedException_wrappedInRuntime() {
        assertThatThrownBy(() -> factory.create(new Exception("checked")))
            .isInstanceOf(RuntimeException.class)
            .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void clientType_returnsCorrectType() {
        assertThat(factory.clientType()).isEqualTo(TestClient.class);
    }
}
