package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.ARRAY_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.Universe.EmbeddingType;

/**
 * A type that represents an array of values of another type
 *
 * @param <V> the type of the values in the array
 * @param <R> the type of the values in the array after reading
 */
public class ArrayType<V, R> extends CondensedType<List<V>, List<R>> {

    private final LazyType<V, R> valueType;
    private final EmbeddingType embedding;

    public ArrayType(
            int id,
            String name,
            String description,
            LazyType<V, R> valueType,
            EmbeddingType embedding) {
        super(id, name, description);
        this.valueType = valueType;
        this.embedding = embedding;
    }

    public ArrayType(
            int id,
            String name,
            String description,
            CondensedType<V, R> valueType,
            EmbeddingType embedding) {
        this(id, name, description, new LazyType<>(valueType), embedding);
    }

    public ArrayType(int id, String name, String description, LazyType<V, R> valueType) {
        this(id, name, description, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, String name, String description, CondensedType<V, R> valueType) {
        this(id, name, description, new LazyType<>(valueType), EmbeddingType.INLINE);
    }

    public ArrayType(int id, LazyType<V, R> valueType) {
        this(id, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, CondensedType<V, R> valueType) {
        this(id, valueType, EmbeddingType.INLINE);
    }

    public ArrayType(int id, LazyType<V, R> valueType, EmbeddingType embedding) {
        this(
                id,
                "array<" + valueType.getName() + ">",
                "An array of " + valueType.getName() + "s",
                valueType,
                embedding);
    }

    public ArrayType(int id, CondensedType<V, R> valueType, EmbeddingType embedding) {
        this(id, new LazyType<>(valueType), embedding);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SpecifiedType<ArrayType<V, R>> getSpecifiedType() {
        return (SpecifiedType<ArrayType<V, R>>) (SpecifiedType<?>) SPECIFIED_TYPE;
    }

    public CondensedType<V, R> getValueType() {
        return valueType.get();
    }

    public int getValueTypeId() {
        return valueType.getId();
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
        switch (embedding) {
            case INLINE, NULLABLE_INLINE -> {
                List<R> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(getValueType().readFrom(in, this, embedding));
                }
                return new ReadList<>(this, list);
            }
            case REFERENCE, REFERENCE_PER_TYPE -> {
                var ids =
                        IntStream.range(0, size)
                                .mapToObj(
                                        i -> {
                                            int val =
                                                    getValueType()
                                                            .readReference(in, this, embedding);
                                            return val == -1 ? null : val;
                                        })
                                .toList();
                return new ReadList<>(
                        this, ids, id -> getValueType().getViaReference(in, this, embedding, id));
            }
        }
        throw new IllegalArgumentException("Invalid embedding type: " + embedding);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && Objects.equals(valueType, ((ArrayType<?, ?>) obj).valueType)
                && embedding == ((ArrayType<?, ?>) obj).embedding;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), valueType);
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
                    out.writeSingleByte(typeInstance.embedding.ordinal());
                }

                @Override
                public ArrayType<?, ?> readInnerTypeSpecification(
                        CondensedInputStream in, int id, String name, String description) {
                    int innerTypeId = (int) in.readUnsignedVarint();
                    long embedding = in.readUnsignedLong(1);
                    var valueType = in.getTypeCollection().getLazyType(innerTypeId);
                    return (ArrayType<?, ?>)
                            in.getTypeCollection()
                                    .addType(
                                            new ArrayType<>(
                                                    id,
                                                    name,
                                                    description,
                                                    valueType,
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
                + "': "
                + valueType.getName()
                + "\n"
                + indentStr
                + "}";
    }

    /**
     * An array type that wraps another type and converts it to an array
     *
     * @param <O> the type of the object to convert
     * @param <V> the type of the values in the array
     * @param <R> the type of the values in the array after reading
     */
    public static class WrappedArrayType<O, V, R> extends CondensedType<O, List<R>> {
        private final ArrayType<V, R> arrayType;
        private final Function<O, List<V>> converter;

        public WrappedArrayType(ArrayType<V, R> arrayType, Function<O, List<V>> converter) {
            super(arrayType.getId(), arrayType.getName(), arrayType.getDescription());
            this.arrayType = arrayType;
            this.converter = converter;
        }

        public static final SpecifiedType<WrappedArrayType<?, ?, ?>> SPECIFIED_TYPE =
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
                    public WrappedArrayType<?, ?, ?> getDefaultType(int id) {
                        throw new NoSuchDefaultTypeException();
                    }

                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public void writeInnerTypeSpecification(
                            CondensedOutputStream out, WrappedArrayType<?, ?, ?> typeInstance) {
                        typeInstance
                                .arrayType
                                .getSpecifiedType()
                                .writeInnerTypeSpecification(
                                        out, (ArrayType) typeInstance.arrayType);
                    }

                    @Override
                    public WrappedArrayType<?, ?, ?> readInnerTypeSpecification(
                            CondensedInputStream in, int id, String name, String description) {
                        return new WrappedArrayType<>(
                                ArrayType.SPECIFIED_TYPE.readInnerTypeSpecification(
                                        in, id, name, description),
                                java.util.function.Function.identity());
                    }

                    @Override
                    public boolean isPrimitive() {
                        return false;
                    }
                };

        @Override
        @SuppressWarnings("unchecked")
        public SpecifiedType<WrappedArrayType<O, V, R>> getSpecifiedType() {
            return (SpecifiedType<WrappedArrayType<O, V, R>>) (SpecifiedType<?>) SPECIFIED_TYPE;
        }

        @Override
        public void writeTo(CondensedOutputStream out, O value) {
            arrayType.writeTo(out, converter.apply(value));
        }

        @Override
        public List<R> readFrom(CondensedInputStream in) {
            return arrayType.readFrom(in);
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj)
                    && arrayType.equals(((WrappedArrayType<?, ?, ?>) obj).arrayType);
        }

        @Override
        public int hashCode() {
            return arrayType.hashCode();
        }
    }
}
