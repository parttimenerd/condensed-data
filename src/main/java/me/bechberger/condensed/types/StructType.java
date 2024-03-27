package me.bechberger.condensed.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Universe.EmbeddingType;
import org.jetbrains.annotations.NotNull;

/** A type that represents a struct */
public class StructType<T> extends CondensedType<T> {

    public record Field<T, F>(
            String name,
            String description,
            CondensedType<? extends F> type,
            Function<T, F> getter,
            EmbeddingType embedding) {

        public Field(
                String name,
                String description,
                CondensedType<? extends F> type,
                Function<T, F> getter) {
            this(name, description, type, getter, EmbeddingType.INLINE);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Field<?, ?>
                    && name.equals(((Field<?, ?>) obj).name)
                    && description.equals(((Field<?, ?>) obj).description)
                    && type.equals(((Field<?, ?>) obj).type)
                    && embedding.equals(((Field<?, ?>) obj).embedding);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, description, type, embedding);
        }
    }

    private final List<Field<T, ?>> fields;
    private final Function<List<?>, T> creator;

    public StructType(
            int id,
            String name,
            String description,
            List<Field<T, ?>> fields,
            Function<List<?>, T> creator) {
        super(id, name, description);
        this.fields = fields;
        this.creator = creator;
    }

    public StructType(int id, List<Field<T, ?>> fields, Function<List<?>, T> creator) {
        this(
                id,
                "struct{"
                        + fields.stream()
                                .map(f -> f.type.getName() + " " + f.name)
                                .collect(Collectors.joining(", "))
                        + "}",
                "A struct with fields: "
                        + fields.stream()
                                .map(
                                        f ->
                                                f.type().getName()
                                                        + " "
                                                        + f.name()
                                                        + (f.description().isEmpty()
                                                                ? ""
                                                                : " - " + f.description())
                                                        + " "
                                                        + f.embedding())
                                .collect(Collectors.joining(", ")),
                fields,
                creator);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SpecifiedType<StructType<T>> getSpecifiedType() {
        return (SpecifiedType<StructType<T>>) (SpecifiedType<?>) SPECIFIED_TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void writeTo(CondensedOutputStream out, T value) {
        for (Field<T, ?> field : fields) {
            var fieldType = ((CondensedType<Object>) field.type());
            var fieldValue = field.getter().apply(value);
            fieldType.writeTo(out, fieldValue, this, field.embedding());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T readFrom(CondensedInputStream in) {
        List<Object> values = new ArrayList<>(fields.size());
        for (Field<T, ?> field : fields) {
            values.add(
                    ((CondensedType<Object>) field.type()).readFrom(in, this, field.embedding()));
        }
        return creator.apply(values);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && fields.equals(((StructType<?>) obj).fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), fields.hashCode());
    }

    public static final SpecifiedType<StructType<?>> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return 5;
                }

                @Override
                public String name() {
                    return "struct";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, StructType<?> type) {
                    out.writeUnsignedVarInt(type.fields.size());
                    for (Field<?, ?> field : type.fields) {
                        out.writeString(field.name());
                        out.writeString(field.description());
                        out.writeTypeId(field.type());
                        out.writeUnsignedLong(field.embedding().ordinal(), 1);
                    }
                }

                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public StructType<Map<String, Object>> readInnerTypeSpecification(
                        CondensedInputStream in, String name, String description) {
                    int size = (int) in.readUnsignedVarint();
                    List<Field<?, ?>> fields = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        String fieldName = in.readString(null);
                        String fieldDescription = in.readString(null);
                        CondensedType<?> fieldType = in.readTypeViaId();
                        EmbeddingType embeddingType =
                                EmbeddingType.valueOf((int) in.readUnsignedLong(1));
                        fields.add(
                                new Field<>(
                                        fieldName,
                                        fieldDescription,
                                        fieldType,
                                        Function.identity(),
                                        embeddingType));
                    }
                    return in.getTypeCollection()
                            .addType(
                                    id ->
                                            new StructType<>(
                                                    id,
                                                    name,
                                                    description,
                                                    (List<Field<Map<String, Object>, ?>>)
                                                            (List) fields,
                                                    (List<?> l) -> construct(l, fields)));
                }

                @NotNull
                private static Map<String, Object> construct(List<?> l, List<Field<?, ?>> fields) {
                    return IntStream.range(0, fields.size())
                            .boxed()
                            .collect(Collectors.toMap(j -> fields.get(j).name, l::get));
                }

                @Override
                public StructType<?> getDefaultType(int id) {
                    throw new NoSuchDefaultTypeException();
                }

                @Override
                public boolean isPrimitive() {
                    return false;
                }
            };

    @Override
    public String toPrettyString(int indent) {
        var indentStr = " ".repeat(indent);
        return indentStr
                + "StrucType{\n"
                + fields.stream()
                        .map(
                                f ->
                                        indentStr
                                                + "  "
                                                + f.name()
                                                + ":\n"
                                                + f.type().toPrettyString(indent + 4))
                        .collect(Collectors.joining(",\n"))
                + "\n"
                + indentStr
                + "}";
    }
}
