package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.ARRAY_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Universe.EmbeddingType;

/**
 * A type that represents an array of values of another type
 *
 * @param <V> the type of the values in the array
 * @param <R> the type of the values in the array after reading
 */
public class ArrayType<V, R> extends CondensedType<List<V>, List<R>> {

    public static class LazyType<T, R> implements Supplier<CondensedType<T, R>> {
        private final int id;
        private final Supplier<CondensedType<T, R>> supplier;
        private final String name;
        private CondensedType<T, R> type;

        public LazyType(int id, Supplier<CondensedType<T, R>> supplier, String name) {
            this.id = id;
            this.supplier = supplier;
            this.name = name;
        }

        @Override
        public CondensedType<T, R> get() {
            if (type == null) {
                type = supplier.get();
            }
            return type;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof LazyType<?, ?>
                    && id == ((LazyType<?, ?>) obj).id
                    && name.equals(((LazyType<?, ?>) obj).name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, id);
        }

        @Override
        public String toString() {
            return name;
        }

        public int getId() {
            return id;
        }
    }

    private final CondensedType<V, R> valueType;
    private final LazyType<V, R> valueTypeSupplier;
    private final EmbeddingType embedding;

    public ArrayType(
            int id,
            String name,
            String description,
            CondensedType<V, R> valueType,
            EmbeddingType embedding) {
        super(id, name, description);
        this.valueType = valueType;
        this.valueTypeSupplier = null;
        this.embedding = embedding;
    }

    public ArrayType(
            int id,
            String name,
            String description,
            LazyType<V, R> valueTypeSupplier,
            EmbeddingType embedding) {
        super(id, name, description);
        this.valueType = null;
        this.valueTypeSupplier = valueTypeSupplier;
        this.embedding = embedding;
    }

    public ArrayType(int id, String name, String description, CondensedType<V, R> valueType) {
        this(id, name, description, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, String name, String description, LazyType<V, R> valueTypeSupplier) {
        this(id, name, description, valueTypeSupplier, EmbeddingType.INLINE);
    }

    public ArrayType(int id, CondensedType<V, R> valueType) {
        this(id, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, LazyType<V, R> valueTypeSupplier) {
        this(id, valueTypeSupplier, EmbeddingType.INLINE);
    }

    public ArrayType(int id, CondensedType<V, R> valueType, EmbeddingType embedding) {
        this(
                id,
                "array<" + valueType.getName() + ">",
                "An array of " + valueType.getDescription(),
                valueType,
                embedding);
    }

    public ArrayType(int id, LazyType<V, R> valueTypeSupplier, EmbeddingType embedding) {
        this(
                id,
                "array<" + valueTypeSupplier.getName() + ">",
                "An array of " + valueTypeSupplier.getName() + "s",
                valueTypeSupplier,
                embedding);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SpecifiedType<ArrayType<V, R>> getSpecifiedType() {
        return (SpecifiedType<ArrayType<V, R>>) (SpecifiedType<?>) SPECIFIED_TYPE;
    }

    public CondensedType<V, R> getValueType() {
        return valueType == null ? valueTypeSupplier.get() : valueType;
    }

    public int getValueTypeId() {
        return valueType == null ? valueTypeSupplier.getId() : valueType.getId();
    }

    @Override
    public void writeTo(CondensedOutputStream out, List<V> value) {
        out.writeUnsignedVarInt(value.size());
        for (V v : value) {
            getValueType().writeTo(out, v, this, embedding);
        }
    }

    @Override
    public List<R> readFrom(CondensedInputStream in) {
        int size = (int) in.readUnsignedVarint();
        List<R> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(getValueType().readFrom(in, this, embedding));
        }
        return list;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && Objects.equals(valueType, ((ArrayType<?, ?>) obj).valueType)
                && Objects.equals(valueTypeSupplier, ((ArrayType<?, ?>) obj).valueTypeSupplier)
                && embedding == ((ArrayType<?, ?>) obj).embedding;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), valueType, valueTypeSupplier);
    }

    public static final SpecifiedType<ArrayType<?, ?>> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return ARRAY_ID;
                }

                @Override
                public String name() {
                    return "array";
                }

                /** No default type for arrays */
                @Override
                public ArrayType<?, ?> getDefaultType(int id) {
                    throw new NoSuchDefaultTypeException();
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, ArrayType<?, ?> typeInstance) {
                    out.writeUnsignedVarInt(typeInstance.getValueTypeId());
                    out.writeUnsignedLong(typeInstance.embedding.ordinal(), 1);
                }

                @Override
                public ArrayType<?, ?> readInnerTypeSpecification(
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
                + (valueType == null
                        ? valueTypeSupplier.getName()
                        : valueType.toPrettyString(indent + 2))
                + "\n"
                + indentStr
                + "}";
    }
}
