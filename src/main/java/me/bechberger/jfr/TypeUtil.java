package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;

import java.time.Duration;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmc.flightrecorder.writer.api.TypedField;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;

@JMCDependent
public class TypeUtil {
    public static long toLong(Object longable) {
        if (longable instanceof Float) {
            return (long) (float) (Float) longable;
        }
        if (longable instanceof Double) {
            return (long) (double) (Double) longable;
        }
        return (long) longable;
    }

    public static @Nullable TypedValue getTypedPrimitiveValue(TypedField field, Object value) {
        if (value == null) {
            return null;
        }
        var type = field.getType();
        if (value instanceof Instant) {
            // improve
            return field.getType().asValue(toNanoSeconds((Instant) value));
        }
        if (value instanceof Duration) {
            return field.getType().asValue(((Duration) value).toNanos());
        }
        return switch (type.getTypeName()) {
            case "boolean" -> type.asValue((boolean) value);
            case "byte" -> type.asValue((byte) toLong(value));
            case "char" -> type.asValue((char) toLong(value));
            case "short" -> type.asValue((short) toLong(value));
            case "int" -> type.asValue((int) toLong(value));
            case "long" -> type.asValue(toLong(value));
            case "float" -> type.asValue((float) value);
            case "double" -> type.asValue((double) (float) value);
            case "String", "java.lang.String" -> type.asValue((String) value);
            default -> {
                if (value instanceof String) {
                    yield type.asValue((String) value);
                }
                yield null;
            }
        };
    }
}
