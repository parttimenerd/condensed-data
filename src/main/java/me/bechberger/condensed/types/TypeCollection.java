package me.bechberger.condensed.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
     * Add a new type to the collection.
     *
     * <p>If you're currently writing a file, make sure to also write it out. Consider using {@link
     * me.bechberger.condensed.CondensedOutputStream#writeAndStoreType(Function)}
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

    CondensedType<?, ?> addType(CondensedType<?, ?> type) {
        int id = type.getId();
        if (id - types.size() > 10000) {
            throw new IllegalStateException(
                    "Type id " + id + " is too far ahead of the last type id " + lastTypeId);
        }
        if (id < 0) {
            throw new IllegalStateException("Type id is negative (" + id + ") which is an error");
        }
        while (types.size() <= id) {
            types.add(null);
        }
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
    public static <T, R> @Nullable R normalize(@Nullable T value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            switch (value) {
                case boolean[] booleans1 -> {
                    List<Boolean> booleans = new ArrayList<>();
                    for (boolean b : booleans1) {
                        booleans.add(b);
                    }
                    return (R) booleans;
                }
                case byte[] bytes -> {
                    List<Long> longs = new ArrayList<>();
                    for (byte b : bytes) {
                        longs.add((long) b);
                    }
                    return (R) longs;
                }
                case short[] shorts -> {
                    List<Long> longs = new ArrayList<>();
                    for (short s : shorts) {
                        longs.add((long) s);
                    }
                    return (R) longs;
                }
                case int[] ints -> {
                    List<Long> longs = new ArrayList<>();
                    for (int i : ints) {
                        longs.add((long) i);
                    }
                    return (R) longs;
                }
                case long[] longs1 -> {
                    return (R) Arrays.stream(longs1).boxed().toList();
                }
                case float[] floats1 -> {
                    List<Float> floats = new ArrayList<>();
                    for (float f : floats1) {
                        floats.add(f);
                    }
                    return (R) floats;
                }
                case double[] doubles -> {
                    List<Float> floats = new ArrayList<>();
                    for (double d : doubles) {
                        floats.add((float) d);
                    }
                    return (R) floats;
                }
                case char[] chars -> {
                    List<Long> longs = new ArrayList<>();
                    for (char c : chars) {
                        longs.add((long) c);
                    }
                    return (R) longs;
                }
                default -> {}
            }
            return (R) Arrays.stream((T[]) value).map(TypeCollection::normalize).toList();
        }
        switch (value) {
            case Float v -> {
                return (R) value;
            }
            case Double v -> {
                return (R) (Float) (float) (double) value;
            }
            case Character c -> {
                return (R) (Long) (long) (char) value;
            }
            case Number number -> {
                switch (value) {
                    case Long l -> {
                        return (R) value;
                    }
                    case Integer i -> {
                        return (R) (Long) (long) (int) value;
                    }
                    case Short i -> {
                        return (R) (Long) (long) (short) value;
                    }
                    case Byte b -> {
                        return (R) (Long) (long) (byte) value;
                    }
                    default -> {}
                }
            }
            default -> {}
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

    public LazyType<?, ?> getLazyType(int id) {
        if (hasType(id)) {
            return new LazyType<>(getType(id));
        }
        return new LazyType<>(id, () -> getType(id), "type " + id);
    }
}
