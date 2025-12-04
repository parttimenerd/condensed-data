package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.StructType.Field;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmc.flightrecorder.writer.api.TypedField;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;

public class TypeUtil {
    private static CondensedType<?, ?> getPrimitiveType(Class<?> klass) {
        return switch (klass.getName()) {
            case "boolean" -> TypeCollection.getDefaultTypeInstance(BooleanType.SPECIFIED_TYPE);
            case "int" -> TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE);
            case "long" -> TypeCollection.getDefaultTypeInstance(VarIntType.SPECIFIED_TYPE);
            case "java.lang.String" ->
                    TypeCollection.getDefaultTypeInstance(StringType.SPECIFIED_TYPE);
            case "float" -> TypeCollection.getDefaultTypeInstance(FloatType.SPECIFIED_TYPE);
            default -> throw new IllegalArgumentException("Unsupported type: " + klass.getName());
        };
    }

    private static Stream<java.lang.reflect.Field> getFieldsForClass(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.getName().startsWith("_"));
    }

    /**
     * Use reflection to create a struct type from a class, assumes that all fields have a primitive
     * type and ignores fields that start with an underscore
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> StructType<T, T> createStructWithPrimitiveFields(
            TypeCollection collection, Class<T> clazz) {
        return collection.addType(
                id -> {
                    var fields =
                            getFieldsForClass(clazz)
                                    .map(
                                            f ->
                                                    new Field<>(
                                                            f.getName(),
                                                            "",
                                                            getPrimitiveType(f.getType()),
                                                            obj -> {
                                                                try {
                                                                    return f.get(obj);
                                                                } catch (IllegalAccessException e) {
                                                                    // try calling the getter
                                                                    try {
                                                                        return clazz.getMethod(
                                                                                        "get"
                                                                                                + f.getName()
                                                                                                        .substring(
                                                                                                                0,
                                                                                                                1)
                                                                                                        .toUpperCase()
                                                                                                + f.getName()
                                                                                                        .substring(
                                                                                                                1))
                                                                                .invoke(obj);
                                                                    } catch (IllegalAccessException
                                                                            | InvocationTargetException
                                                                            | NoSuchMethodException
                                                                                    e1) {
                                                                        // try calling the record
                                                                        // getter
                                                                        try {
                                                                            return clazz.getMethod(
                                                                                            f
                                                                                                    .getName())
                                                                                    .invoke(obj);
                                                                        } catch (IllegalAccessException
                                                                                | InvocationTargetException
                                                                                | NoSuchMethodException
                                                                                        e2) {
                                                                            throw new RuntimeException(
                                                                                    e2);
                                                                        }
                                                                    }
                                                                }
                                                            }))
                                    .collect(Collectors.toList());
                    return new StructType<>(
                            id,
                            clazz.getCanonicalName(),
                            "",
                            (List<Field<T, ?, ?>>) (List) fields,
                            r -> createInstanceFromReadStruct(clazz, r));
                });
    }

    @SuppressWarnings("unchecked")
    public static <T> T createInstanceFromReadStruct(Class<T> klass, ReadStruct readStruct) {
        var members = readStruct.ensureComplete();
        var args =
                getFieldsForClass(klass)
                        .filter(f -> members.containsKey(f.getName()))
                        .map(f -> members.get(f.getName()))
                        .toArray();
        var constructor =
                Arrays.stream(klass.getDeclaredConstructors())
                        .filter(c -> c.getParameterCount() == args.length)
                        .findFirst();
        if (constructor.isEmpty()) {
            throw new AssertionError("Can't find suitable constructor for " + klass);
        }
        try {
            return (T) constructor.get().newInstance(args);
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
    }

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
