package me.bechberger.condensed.types;

import java.util.ArrayList;
import java.util.List;
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

    static {
        specifiedTypes[0] = IntType.SPECIFIED_TYPE;
        specifiedTypes[1] = VarIntType.SPECIFIED_TYPE;
        specifiedTypes[2] = FloatType.SPECIFIED_TYPE;
specifiedTypes[3] = StringType.SPECIFIED_TYPE;
    }

    private final List<@Nullable CondensedType<?>> types;

    public TypeCollection() {
        this.types = new ArrayList<>();
        initDefaultTypes();
    }

    private void initDefaultTypes() {
        for (int i = 0; i < specifiedTypes.length; i++) {
            if (specifiedTypes[i] != null) {
                types.add(specifiedTypes[i].getDefaultType(i));
            } else {
                types.add(null);
            }
        }
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
    public <C extends CondensedType<?>> C addType(Function<Integer, C> typeCreator) {
        int id = types.size();
        var type = typeCreator.apply(id);
        types.add(type);
        return type;
    }

    /**
     * Get the type instance with the given id
     *
     * @param id the id of the type
     * @return the type
     * @throws NoSuchTypeException if no type with the given id exists
     */
    public CondensedType<?> getType(int id) {
        if (!hasType(id)) {
            throw new NoSuchTypeException(id);
        }
        return types.get(id);
    }
}
