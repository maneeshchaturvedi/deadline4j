package io.deadline4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineExceededExceptionTest {

    @Test
    void messageOnlyConstructor() {
        DeadlineExceededException ex = new DeadlineExceededException("deadline exceeded");
        assertThat(ex.getMessage()).isEqualTo("deadline exceeded");
        assertThat(ex.serviceName()).isNull();
        assertThat(ex.budgetRemainingMs()).isEqualTo(-1);
    }

    @Test
    void fullConstructor() {
        DeadlineExceededException ex = new DeadlineExceededException(
            "timeout calling orders", "order-service", 42);
        assertThat(ex.getMessage()).isEqualTo("timeout calling orders");
        assertThat(ex.serviceName()).isEqualTo("order-service");
        assertThat(ex.budgetRemainingMs()).isEqualTo(42);
    }

    @Test
    void serviceName_getter() {
        DeadlineExceededException ex = new DeadlineExceededException(
            "msg", "payment-svc", 100);
        assertThat(ex.serviceName()).isEqualTo("payment-svc");
    }

    @Test
    void budgetRemainingMs_getter() {
        DeadlineExceededException ex = new DeadlineExceededException(
            "msg", "svc", 999);
        assertThat(ex.budgetRemainingMs()).isEqualTo(999);
    }

    @Test
    void extendsRuntimeException() {
        DeadlineExceededException ex = new DeadlineExceededException("test");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
