package io.deadline4j.spring.openfeign;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultReturnValuesTest {

    @Test
    void voidPrimitive_returnsNull() {
        assertThat(DefaultReturnValues.forType(void.class)).isNull();
    }

    @Test
    void voidWrapper_returnsNull() {
        assertThat(DefaultReturnValues.forType(Void.class)).isNull();
    }

    @Test
    void optional_returnsEmpty() {
        assertThat(DefaultReturnValues.forType(Optional.class))
            .isEqualTo(Optional.empty());
    }

    @Test
    void list_returnsEmptyList() {
        Object result = DefaultReturnValues.forType(List.class);
        assertThat(result).isEqualTo(Collections.emptyList());
    }

    @Test
    void set_returnsEmptySet() {
        Object result = DefaultReturnValues.forType(Set.class);
        assertThat(result).isEqualTo(Collections.emptySet());
    }

    @Test
    void map_returnsEmptyMap() {
        Object result = DefaultReturnValues.forType(Map.class);
        assertThat(result).isEqualTo(Collections.emptyMap());
    }

    @Test
    void booleanPrimitive_returnsFalse() {
        assertThat(DefaultReturnValues.forType(boolean.class)).isEqualTo(false);
    }

    @Test
    void booleanWrapper_returnsFalse() {
        assertThat(DefaultReturnValues.forType(Boolean.class)).isEqualTo(false);
    }

    @Test
    void intPrimitive_returnsZero() {
        assertThat(DefaultReturnValues.forType(int.class)).isEqualTo(0);
    }

    @Test
    void intWrapper_returnsZero() {
        assertThat(DefaultReturnValues.forType(Integer.class)).isEqualTo(0);
    }

    @Test
    void longPrimitive_returnsZero() {
        assertThat(DefaultReturnValues.forType(long.class)).isEqualTo(0L);
    }

    @Test
    void longWrapper_returnsZero() {
        assertThat(DefaultReturnValues.forType(Long.class)).isEqualTo(0L);
    }

    @Test
    void doublePrimitive_returnsZero() {
        assertThat(DefaultReturnValues.forType(double.class)).isEqualTo(0.0);
    }

    @Test
    void doubleWrapper_returnsZero() {
        assertThat(DefaultReturnValues.forType(Double.class)).isEqualTo(0.0);
    }

    @Test
    void floatPrimitive_returnsZero() {
        assertThat(DefaultReturnValues.forType(float.class)).isEqualTo(0.0f);
    }

    @Test
    void floatWrapper_returnsZero() {
        assertThat(DefaultReturnValues.forType(Float.class)).isEqualTo(0.0f);
    }

    @Test
    void string_returnsNull() {
        assertThat(DefaultReturnValues.forType(String.class)).isNull();
    }

    @Test
    void customClass_returnsNull() {
        assertThat(DefaultReturnValues.forType(DefaultReturnValuesTest.class)).isNull();
    }

    @Test
    void shortPrimitive_returnsZero() {
        assertThat(DefaultReturnValues.forType(short.class)).isEqualTo((short) 0);
    }

    @Test
    void byteWrapper_returnsZero() {
        assertThat(DefaultReturnValues.forType(Byte.class)).isEqualTo((byte) 0);
    }

    @Test
    void charPrimitive_returnsNullChar() {
        assertThat(DefaultReturnValues.forType(char.class)).isEqualTo('\0');
    }
}
