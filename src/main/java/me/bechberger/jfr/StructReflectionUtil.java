package me.bechberger.jfr;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.BooleanType;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.FloatType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StringType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.condensed.types.TypeCollection;
import me.bechberger.condensed.types.VarIntType;

/** Reflection utilities that must remain available in inflaterless/JMC-free builds. */
public class StructReflectionUtil {

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
        return ReadStructUtil.getFieldsForClass(clazz);
    }

    /**
     * Use reflection to create a struct type from a class, assumes that all fields have a primitive
     * type and ignores fields that start with an underscore.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> StructType<T, T> createStructWithPrimitiveFields(
            TypeCollection collection, Class<T> clazz) {
        return collection.addType(
                id -> {
                    List<Field<T, ?, ?>> fields =
                            (List)
                                    getFieldsForClass(clazz)
                                            .map(
                                                    f ->
                                                            new Field(
                                                                    f.getName(),
                                                                    "",
                                                                    getPrimitiveType(f.getType()),
                                                                    obj -> {
                                                                        try {
                                                                            return f.get(obj);
                                                                        } catch (
                                                                                IllegalAccessException
                                                                                        e) {
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
                                                                                        .invoke(
                                                                                                obj);
                                                                            } catch (IllegalAccessException
                                                                                    | InvocationTargetException
                                                                                    | NoSuchMethodException
                                                                                            e1) {
                                                                                try {
                                                                                    return clazz.getMethod(
                                                                                                    f
                                                                                                            .getName())
                                                                                            .invoke(
                                                                                                    obj);
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
                            fields,
                            r -> createInstanceFromReadStruct(clazz, r));
                });
    }

    public static <T> T createInstanceFromReadStruct(Class<T> klass, ReadStruct readStruct) {
        return ReadStructUtil.createInstanceFromReadStruct(klass, readStruct.ensureComplete());
    }
}
