package me.bechberger.condensed.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Universe.EmbeddingType;

/**
 * A type that represents an array of values of another type
 *
 * @param <V> the type of the values in the array
 */
public class ArrayType<V> extends CondensedType<List<V>> {

    private final CondensedType<V> valueType;
    private final EmbeddingType embedding;

    public ArrayType(
            int id,
            String name,
            String description,
            CondensedType<V> valueType,
            EmbeddingType embedding) {
        super(id, name, description);
        this.valueType = valueType;
        this.embedding = embedding;
    }

    public ArrayType(int id, String name, String description, CondensedType<V> valueType) {
        this(id, name, description, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, CondensedType<V> valueType) {
        this(id, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, CondensedType<V> valueType, EmbeddingType embedding) {
        this(
                id,
                "array<" + valueType.getName() + ">",
                "An array of " + valueType.getDescription(),
                valueType,
                embedding);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SpecifiedType<ArrayType<V>> getSpecifiedType() {
        return (SpecifiedType<ArrayType<V>>) (SpecifiedType<?>) SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, List<V> value) {
        out.writeUnsignedVarInt(value.size());
        for (V v : value) {
            valueType.writeTo(out, v, this, embedding);
        }
    }

    @Override
    public List<V> readFrom(CondensedInputStream in) {
        int size = (int) in.readUnsignedVarint();
        List<V> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(valueType.readFrom(in, this, embedding));
        }
        return list;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && valueType.equals(((ArrayType<?>) obj).valueType)
                && embedding == ((ArrayType<?>) obj).embedding;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), valueType);
    }

    public static final SpecifiedType<ArrayType<?>> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return 4;
                }

                @Override
                public String name() {
                    return "array";
                }

                /** No default type for arrays */
                @Override
                public ArrayType<?> getDefaultType(int id) {
                    throw new NoSuchDefaultTypeException();
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, ArrayType<?> typeInstance) {
                    out.writeUnsignedVarInt(typeInstance.valueType.getId());
                    out.writeUnsignedLong(typeInstance.embedding.ordinal(), 1);
                }

                @Override
                public ArrayType<?> readInnerTypeSpecification(
                        CondensedInputStream in, String name, String description) {
                    long innerTypeId = in.readUnsignedVarint();
                    long embedding = in.readUnsignedLong(1);
                    return in.getTypeCollection()
                            .addType(
                                    id ->
                                            new ArrayType<>(
                                                    id,
                                                    name,
                                                    description,
                                                    in.getTypeCollection()
                                                            .getType((int) innerTypeId),
                                                    EmbeddingType.values()[(int) embedding]));
                }

                @Override
                public boolean isPrimitive() {
                    return false;
                }
            };

    @Override
    public String toPrettyString(int indent) {
        String indentStr = " ".repeat(indent);
        return indentStr
                + "ArrayType { id = "
                + getId()
                + ", name = '"
                + getName()
                + "', description = '"
                + getDescription()
                + "'\n"
                + valueType.toPrettyString(indent + 2)
                + "\n"
                + indentStr
                + "}";
    }
}
