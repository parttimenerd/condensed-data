package me.bechberger.condensed.types;

import java.util.*;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/** Collection of all available {@link SpecifiedType} and {@link CondensedType} instances */
public class TypeCollection {

    public static class NoSuchTypeException extends RuntimeException {
        public NoSuchTypeException(int id) {
            super("No type with id " + id);
        }
    }

    public static class NoSuchSpecifiedTypeException extends RuntimeException {
        public NoSuchSpecifiedTypeException(int id) {
            super("No specified type with id " + id);
        }
    }

    public static final int MAX_SPECIFIED_TYPES = 15;
    public static final int FIRST_CUSTOM_TYPE_ID = MAX_SPECIFIED_TYPES + 1;
    private static final SpecifiedType<?>[] specifiedTypes =
            new SpecifiedType<?>[MAX_SPECIFIED_TYPES + 1];
    private static final CondensedType<?, ?>[] defaultTypes =
            new CondensedType<?, ?>[MAX_SPECIFIED_TYPES + 1];

    public static final int INT_ID = 0;
    public static final int VAR_INT_ID = 1;
    public static final int BOOLEAN_ID = 2;
    public static final int FLOAT_ID = 3;
    public static final int STRING_ID = 4;
    public static final int ARRAY_ID = 5;
    public static final int STRUCT_ID = 6;

    static {
        specifiedTypes[INT_ID] = IntType.SPECIFIED_TYPE;
        specifiedTypes[VAR_INT_ID] = VarIntType.SPECIFIED_TYPE;
        specifiedTypes[BOOLEAN_ID] = BooleanType.SPECIFIED_TYPE;
        specifiedTypes[FLOAT_ID] = FloatType.SPECIFIED_TYPE;
        specifiedTypes[STRING_ID] = StringType.SPECIFIED_TYPE;
        specifiedTypes[ARRAY_ID] = ArrayType.SPECIFIED_TYPE;
        specifiedTypes[STRUCT_ID] = StructType.SPECIFIED_TYPE;

        for (int i = 0; i < specifiedTypes.length; i++) {
            if (specifiedTypes[i] != null && specifiedTypes[i].isPrimitive()) {
                defaultTypes[i] = specifiedTypes[i].getDefaultType(i);
            }
        }
    }

    private final List<@Nullable CondensedType<?, ?>> types;
    private int lastTypeId = FIRST_CUSTOM_TYPE_ID - 1;

    public TypeCollection() {
        this.types = new ArrayList<>();
        initDefaultTypes();
    }

    private void initDefaultTypes() {
        types.addAll(Arrays.asList(defaultTypes));
    }

    /**
     * Does the given id signal a specified type?
     *
     * @param id the id to check
     * @return whether the id is a specified type
     */
    public static boolean isSpecifiedType(int id) {
        return id >= 0 && id < specifiedTypes.length && specifiedTypes[id] != null;
    }

    /**
     * Does the given type id have associated {@link CondensedType} instance?
     *
     * @param id the id to check
     * @return whether the id has an associated type
     */
    public boolean hasType(int id) {
        return id >= 0 && id < types.size() && types.get(id) != null;
    }

    /**
     * Get the specified type with the given id
     *
     * @param id the id of the specified type
     * @return the specified type
     * @throws NoSuchSpecifiedTypeException if no specified type with the given id exists
     */
    public static SpecifiedType<?> getSpecifiedType(int id) {
        if (!isSpecifiedType(id)) {
            throw new NoSuchSpecifiedTypeException(id);
        }
        return specifiedTypes[id];
    }

    /**
     * Add a new type to the collection
     *
     * @param typeCreator the function to create the type instance
     * @return the id of the added type
     */
    public <C extends CondensedType<?, ?>> C addType(Function<Integer, C> typeCreator) {
        int id = ++lastTypeId;
        types.add(null);
        var type = typeCreator.apply(id);
        if (types.get(id) != null) {
            throw new IllegalStateException("Type with id " + id + " already exists");
        }
        types.set(id, type);
        return type;
    }

    /**
     * Get the type instance with the given id
     *
     * @param id the id of the type
     * @return the type
     * @throws NoSuchTypeException if no type with the given id exists
     */
    public CondensedType<?, ?> getType(int id) {
        if (!hasType(id)) {
            throw new NoSuchTypeException(id);
        }
        return types.get(id);
    }

    @SuppressWarnings("unchecked")
    public static <T, R> CondensedType<T, R> getDefaultTypeInstance(
            SpecifiedType<? extends CondensedType<T, R>> specifiedType) {
        if (!specifiedType.isPrimitive()
                || specifiedType.id() >= defaultTypes.length
                || defaultTypes[specifiedType.id()] == null) {
            throw new SpecifiedType.NoSuchDefaultTypeException();
        }
        return (CondensedType<T, R>) defaultTypes[specifiedType.id()];
    }

    public boolean containsType(CondensedType<?, ?> type) {
        return types.contains(type);
    }

    /** Normalize values, as the types don't work with primitive array or number classes */
    @SuppressWarnings("unchecked")
    public static <T, R> R normalize(T value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            if (value instanceof boolean[]) {
                List<Boolean> booleans = new ArrayList<>();
                for (boolean b : (boolean[]) value) {
                    booleans.add(b);
                }
                return (R) booleans;
            }
            if (value instanceof byte[]) {
                List<Long> longs = new ArrayList<>();
                for (byte b : (byte[]) value) {
                    longs.add((long) b);
                }
                return (R) longs;
            }
            if (value instanceof short[]) {
                List<Long> longs = new ArrayList<>();
                for (short s : (short[]) value) {
                    longs.add((long) s);
                }
                return (R) longs;
            }
            if (value instanceof int[]) {
                List<Long> longs = new ArrayList<>();
                for (int i : (int[]) value) {
                    longs.add((long) i);
                }
                return (R) longs;
            }
            if (value instanceof long[]) {
                return (R) Arrays.stream((long[]) value).boxed().toList();
            }
            if (value instanceof float[]) {
                List<Float> floats = new ArrayList<>();
                for (float f : (float[]) value) {
                    floats.add(f);
                }
                return (R) floats;
            }
            if (value instanceof double[]) {
                List<Float> floats = new ArrayList<>();
                for (double d : (double[]) value) {
                    floats.add((float) d);
                }
                return (R) floats;
            }
            if (value instanceof char[]) {
                List<Long> longs = new ArrayList<>();
                for (char c : (char[]) value) {
                    longs.add((long) c);
                }
                return (R) longs;
            }
            return (R) Arrays.stream((T[]) value).map(TypeCollection::normalize).toList();
        }
        if (value instanceof Float) {
            return (R) value;
        }
        if (value instanceof Double) {
            return (R) (Float) (float) (double) value;
        }
        if (value instanceof Character) {
            return (R) (Long) (long) (char) value;
        }
        if (value instanceof Number) {
            if (value instanceof Long) {
                return (R) value;
            } else if (value instanceof Integer) {
                return (R) (Long) (long) (int) value;
            } else if (value instanceof Short) {
                return (R) (Long) (long) (short) value;
            } else if (value instanceof Byte) {
                return (R) (Long) (long) (byte) value;
            }
        }
        return (R) value;
    }

    public List<CondensedType<?, ?>> getTypes() {
        return types.stream().filter(Objects::nonNull).toList();
    }

    /** Slow way to get types by name */
    public @Nullable CondensedType<?, ?> getTypeOrNull(String name) {
        return types.stream()
                .filter(t -> t != null && t.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
