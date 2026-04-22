package me.bechberger.jfr;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reflection utilities for creating instances from {@link me.bechberger.condensed.ReadStruct}. Not
 * annotated with {@link JMCDependent} so it survives inflaterless builds.
 */
public class ReadStructUtil {

    static Stream<java.lang.reflect.Field> getFieldsForClass(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.getName().startsWith("_"));
    }

    @SuppressWarnings("unchecked")
    public static <T> T createInstanceFromReadStruct(Class<T> klass, Map<String, Object> members) {
        // Use the constructor with the most parameters (canonical for records)
        var constructor =
                Arrays.stream(klass.getDeclaredConstructors())
                        .max(
                                (c1, c2) ->
                                        Integer.compare(
                                                c1.getParameterCount(), c2.getParameterCount()))
                        .orElseThrow(() -> new AssertionError("No constructor found for " + klass));
        // Use field declaration order (matches canonical constructor param order for records)
        var fields = getFieldsForClass(klass).toList();
        var args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < fields.size() && i < args.length; i++) {
            var field = fields.get(i);
            if (members.containsKey(field.getName())) {
                args[i] = members.get(field.getName());
            } else {
                args[i] = getDefaultValue(field.getType());
            }
        }
        // Fill remaining args with defaults (fields added after the stored version)
        for (int i = fields.size(); i < args.length; i++) {
            args[i] = getDefaultValue(constructor.getParameterTypes()[i]);
        }
        try {
            return (T) constructor.newInstance(args);
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
    }

    static Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
