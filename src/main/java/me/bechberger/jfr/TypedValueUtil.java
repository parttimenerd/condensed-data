package me.bechberger.jfr;

import static me.bechberger.condensed.types.TypeCollection.normalize;

import java.util.Objects;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;

public class TypedValueUtil {
    public static long getLong(TypedValue value, String name) {
        return (long) Objects.requireNonNullElse(normalize(get(value, name)), 0L);
    }

    public static Object get(TypedValue value, String name) {
        return value.getFieldValues().stream()
                .filter(f -> f.getField().getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue()
                .getValue();
    }

    public static Object getNonScalar(TypedValue value, String name) {
        return value.getFieldValues().stream()
                .filter(f -> f.getField().getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue();
    }
}
