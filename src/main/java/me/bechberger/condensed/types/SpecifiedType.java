package me.bechberger.condensed.types;

import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

/**
 * Abstract type which can read and write its specific type instances from and to a {@see
 * CondensedInputStream} and {@see CondensedOutputStream}
 */
public interface SpecifiedType<T extends CondensedType<?, ?>> {

    class NoSuchDefaultTypeException extends RuntimeException {
        public NoSuchDefaultTypeException() {
            super("No default type specified");
        }
    }

    /** The unique id of this abstract type */
    int id();

    String name();

    /** Write the type specification to the stream (excluding the header) */
    default void writeTypeSpecification(CondensedOutputStream out, T typeInstance) {
        out.writeUnsignedVarInt(typeInstance.getId());
        out.writeString(typeInstance.getName());
        out.writeString(typeInstance.getDescription());
        writeInnerTypeSpecification(out, typeInstance);
    }

    /**
     * Write the inner type specification to the stream (excluding the type, name and description)
     */
    void writeInnerTypeSpecification(CondensedOutputStream out, T typeInstance);

    /** Read the type specification from the stream (excluding the type) */
    default T readTypeSpecification(CondensedInputStream in) {
        var t =
                readInnerTypeSpecification(
                        in, (int) in.readUnsignedVarint(), in.readString(), in.readString());
        return t;
    }

    /**
     * Read the inner type specification from the stream (excluding the type, name and description)
     */
    T readInnerTypeSpecification(CondensedInputStream in, int id, String name, String description);

    /**
     * Get the default type instance for this type, if primitive
     *
     * @return the default type instance
     * @throws NoSuchDefaultTypeException if no default type is specified
     */
    T getDefaultType(int id);

    /** Whether this type is a primitive type that doesn't have nested types */
    boolean isPrimitive();
}
