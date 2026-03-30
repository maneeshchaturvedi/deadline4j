package io.deadline4j.spring.openfeign;

import java.util.*;

/**
 * Computes sensible default return values by type.
 * Used by fallback factory when optional calls are skipped.
 */
final class DefaultReturnValues {

    private DefaultReturnValues() {}

    static Object forType(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == Optional.class) return Optional.empty();
        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        if (Set.class.isAssignableFrom(type)) return Collections.emptySet();
        if (Map.class.isAssignableFrom(type)) return Collections.emptyMap();
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0;
        if (type == float.class || type == Float.class) return 0.0f;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == char.class || type == Character.class) return '\0';
        return null;
    }
}
