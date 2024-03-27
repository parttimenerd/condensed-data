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
public class StructType<T, R> extends CondensedType<T, R> {

    public record Field<T, F, R>(
            String name,
            String description,
            CondensedType<? extends F, R> type,
            Function<T, F> getter,
            EmbeddingType embedding) {

        public Field(
                String name,
                String description,
                CondensedType<? extends F, R> type,
                Function<T, F> getter) {
            this(name, description, type, getter, EmbeddingType.INLINE);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Field)) {
                return false;
            }
            Field<?, ?, ?> other = (Field<?, ?, ?>) obj;
            return name.equals(other.name)
                    && description.equals(other.description)
                    && type.equals(other.type)
                    && embedding == other.embedding;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, description, type, embedding);
        }
    }

    private final List<Field<T, ?, ?>> fields;
    private final Map<String, Field<T, ?, ?>> fieldMap;
    private final Function<List<?>, R> creator;

    public StructType(
            int id,
            String name,
            String description,
            List<Field<T, ?, ?>> fields,
            Function<List<?>, R> creator) {
        super(id, name, description);
        this.fields = fields;
        this.fieldMap = fields.stream().collect(Collectors.toMap(Field::name, Function.identity()));
        this.creator = creator;
    }

    public StructType(int id, List<Field<T, ?, ?>> fields, Function<List<?>, R> creator) {
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
    public SpecifiedType<StructType<T, R>> getSpecifiedType() {
        return (SpecifiedType<StructType<T, R>>) (SpecifiedType<?>) SPECIFIED_TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void writeTo(CondensedOutputStream out, T value) {
        for (Field<T, ?, ?> field : fields) {
            var fieldType = ((CondensedType<Object, Object>) field.type());
            var fieldValue = field.getter().apply(value);
            fieldType.writeTo(out, fieldValue, this, field.embedding());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public R readFrom(CondensedInputStream in) {
        List<Object> values = new ArrayList<>(fields.size());
        for (Field<T, ?, ?> field : fields) {
            values.add(
                    ((CondensedType<Object, Object>) field.type())
                            .readFrom(in, this, field.embedding()));
        }
        return creator.apply(values);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && fields.equals(((StructType<?, ?>) obj).fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), fields.hashCode());
    }

    public static final SpecifiedType<StructType<?, ?>> SPECIFIED_TYPE =
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
                        CondensedOutputStream out, StructType<?, ?> type) {
                    out.writeUnsignedVarInt(type.fields.size());
                    for (Field<?, ?, ?> field : type.fields) {
                        out.writeString(field.name());
                        out.writeString(field.description());
                        out.writeTypeId(field.type());
                        out.writeUnsignedLong(field.embedding().ordinal(), 1);
                    }
                }

                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public StructType<Map<String, Object>, Map<String, Object>>
                        readInnerTypeSpecification(
                                CondensedInputStream in, String name, String description) {
                    int size = (int) in.readUnsignedVarint();
                    List<Field<?, ?, ?>> fields = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        String fieldName = in.readString(null);
                        String fieldDescription = in.readString(null);
                        CondensedType<?, ?> fieldType = in.readTypeViaId();
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
                                                    (List<Field<Map<String, Object>, ?, ?>>)
                                                            (List) fields,
                                                    (List<?> l) -> construct(l, fields)));
                }

                @NotNull
                private static Map<String, Object> construct(
                        List<?> l, List<Field<?, ?, ?>> fields) {
                    return IntStream.range(0, fields.size())
                            .boxed()
                            .collect(Collectors.toMap(j -> fields.get(j).name, l::get));
                }

                @Override
                public StructType<?, ?> getDefaultType(int id) {
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

    public Field<T, ?, ?> getField(String name) {
        return fieldMap.get(name);
    }
}
