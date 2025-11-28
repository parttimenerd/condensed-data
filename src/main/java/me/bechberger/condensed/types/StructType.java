package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.STRUCT_ID;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.stats.WriteCause;
import org.jetbrains.annotations.Nullable;

/** A type that represents a struct */
public class StructType<T, R> extends CondensedType<T, R> {

    /*
     * A field in a struct
     * @param <T> the type of the struct
     * @param <F> the type of the field
     * @param <R> the type of the field's reduction
     */
    public static final class Field<T, F, R> {
        private final String name;
        private final String description;
        private final LazyType<? extends F, R> type;
        private final Function<T, F> getter;
        private final EmbeddingType embedding;
        private final int reductionId;
        private final int hashCode;

        public Field(
                String name,
                String description,
                LazyType<? extends F, R> type,
                Function<T, F> getter,
                EmbeddingType embedding,
                int reductionId) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.getter = getter;
            this.embedding = embedding;
            this.reductionId = reductionId;
            this.hashCode = Objects.hash(name, description, type, embedding);
        }

        public Field(
                String name,
                String description,
                CondensedType<? extends F, R> type,
                Function<T, F> getter,
                EmbeddingType embedding,
                int reductionId) {
            this(name, description, new LazyType<>(type), getter, embedding, reductionId);
        }

        public Field(
                String name,
                String description,
                CondensedType<? extends F, R> type,
                Function<T, F> getter,
                EmbeddingType embedding) {
            this(name, description, type, getter, embedding, 0);
        }

        public Field(
                String name,
                String description,
                LazyType<? extends F, R> type,
                Function<T, F> getter,
                int reductionId) {
            this(name, description, type, getter, EmbeddingType.INLINE, reductionId);
        }

        public Field(
                String name,
                String description,
                CondensedType<? extends F, R> typeSupplier,
                Function<T, F> getter,
                int reductionId) {
            this(name, description, new LazyType<>(typeSupplier), getter, reductionId);
        }

        public Field(
                String name,
                String description,
                CondensedType<? extends F, R> typeSupplier,
                Function<T, F> getter) {
            this(name, description, typeSupplier, getter, 0);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Field<?, ?, ?> other)) {
                return false;
            }
            return hashCode == other.hashCode
                    && name.equals(other.name)
                    && description.equals(other.description)
                    && type.equals(other.type)
                    && embedding == other.embedding;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public CondensedType<? extends F, R> type() {
            return type.get();
        }

        public Function<T, F> getter() {
            return getter;
        }

        public EmbeddingType embedding() {
            return embedding;
        }

        public int reductionId() {
            return reductionId;
        }

        @Override
        public String toString() {
            return "Field["
                    + "name="
                    + name
                    + ", "
                    + "description="
                    + description
                    + ", "
                    + "type="
                    + type
                    + ", "
                    + "getter="
                    + getter
                    + ", "
                    + "embedding="
                    + embedding
                    + ']';
        }

        public String getTypeName() {
            return type.getName();
        }

        public int getTypeId() {
            return type.getId();
        }
    }

    private final List<Field<T, ?, ?>> fields;
    private final Map<String, Field<T, ?, ?>> fieldMap;
    private final Function<ReadStruct, R> creator;
    private final StructType<?, ReadStruct> readStructType;
    private final int reductionId;
    private final int hashCode;

    @SuppressWarnings("unchecked")
    private StructType(
            int id,
            String name,
            String description,
            List<Field<T, ?, ?>> fields,
            Function<ReadStruct, R> creator,
            @Nullable StructType<?, ReadStruct> readStructType,
            int reductionId) {
        super(id, name, description);
        this.fields = fields;
        this.fieldMap = fields.stream().collect(Collectors.toMap(Field::name, Function.identity()));
        this.creator = creator;
        this.readStructType =
                readStructType == null ? (StructType<?, ReadStruct>) this : readStructType;
        this.reductionId = reductionId;
        this.hashCode = Objects.hash(super.hashCode(), fields, creator, reductionId);
    }

    public StructType(
            int id,
            String name,
            String description,
            List<Field<T, ?, ?>> fields,
            Function<ReadStruct, R> creator,
            int reductionId) {
        this(
                id,
                name,
                description,
                fields,
                creator,
                new StructType<>(id, name, description, fields, r -> r, null, 0),
                reductionId);
    }

    public StructType(
            int id,
            String name,
            String description,
            List<Field<T, ?, ?>> fields,
            Function<ReadStruct, R> creator) {
        this(id, name, description, fields, creator, 0);
    }

    @SuppressWarnings("unchecked")
    public StructType(int id, List<Field<T, ?, ?>> fields) {
        this(id, fields, r -> (R) r, 0);
    }

    @SuppressWarnings("unchecked")
    public StructType(int id, String name, List<Field<T, ?, ?>> fields) {
        this(id, name, "", fields, r -> (R) r, 0);
    }

    public StructType(int id, List<Field<T, ?, ?>> fields, Function<ReadStruct, R> creator) {
        this(id, fields, creator, 0);
    }

    public StructType(
            int id, List<Field<T, ?, ?>> fields, Function<ReadStruct, R> creator, int reductionId) {
        this(
                id,
                "struct{"
                        + fields.stream()
                                .map(f -> f.getTypeName() + " " + f.name)
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
                creator,
                reductionId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SpecifiedType<StructType<T, R>> getSpecifiedType() {
        return (SpecifiedType<StructType<T, R>>) (SpecifiedType<?>) SPECIFIED_TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void writeTo(CondensedOutputStream out, T value) {
        try (var t = out.getStatistics().withWriteCauseContext(this)) {
            var val = out.getReductions().reduce(reductionId, value);
            for (Field<T, ?, ?> field : fields) {
                var fieldType = ((CondensedType<Object, Object>) field.type());
                var fieldValue =
                        out.getReductions().reduce(field.reductionId, field.getter().apply((T) val));
                fieldType.writeTo(out, fieldValue, this, field.embedding());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public R readFrom(CondensedInputStream in) {
        try (var t = in.getStatistics().withWriteCauseContext(this)) {
            Map<String, Object> values = new HashMap<>();
            Map<String, @Nullable Integer> idsOrNull = new HashMap<>();
            for (Field<T, ?, ?> field : fields) {
                if (field.embedding() == EmbeddingType.INLINE
                    || field.embedding() == EmbeddingType.NULLABLE_INLINE) {
                    var value =
                            ((CondensedType<Object, Object>) field.type())
                                    .readFrom(in, this, field.embedding());
                    values.put(field.name(), in.getReductions().inflate(field.reductionId, value));
                } else {
                    var ref = field.type().readReference(in, this, field.embedding());
                    idsOrNull.put(field.name(), ref == -1 ? null : ref);
                }
            }
            ReadStruct readStruct;
            if (idsOrNull.isEmpty()) {
                readStruct = new ReadStruct(readStructType, values);
            } else {
                readStruct =
                        new ReadStruct(
                                readStructType,
                                values,
                                idsOrNull,
                                (field, id) ->
                                        in.getReductions()
                                                .inflate(
                                                        field.reductionId,
                                                        field.type()
                                                                .getViaReference(
                                                                        in,
                                                                        this,
                                                                        field.embedding,
                                                                        id)));
            }
            return in.getReductions().inflate(reductionId, creator.apply(readStruct));
        }
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && fields.equals(((StructType<?, ?>) obj).fields);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public static final SpecifiedType<StructType<?, ?>> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return STRUCT_ID;
                }

                @Override
                public String name() {
                    return "struct";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, StructType<?, ?> type) {
                    out.writeUnsignedVarInt(type.fields.size());
                    for (StructType.Field<?, ?, ?> field : type.fields) {
                        out.writeString(field.name());
                        out.writeString(field.description());
                        out.writeUnsignedVarInt(field.getTypeId());
                        out.writeSingleByte(field.embedding().ordinal());
                        out.writeUnsignedVarInt(field.reductionId);
                    }
                    out.writeUnsignedVarInt(type.reductionId);
                }

                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public StructType<Map<String, Object>, Map<String, Object>>
                        readInnerTypeSpecification(
                                CondensedInputStream in, int id, String name, String description) {
                    int size = (int) in.readUnsignedVarint();
                    List<Field<?, ?, ?>> fields = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        String fieldName = in.readString(null);
                        String fieldDescription = in.readString(null);
                        var innerTypeId = (int) in.readUnsignedVarint();
                        var innerLazyType = in.getTypeCollection().getLazyType(innerTypeId);
                        EmbeddingType embeddingType =
                                EmbeddingType.valueOf((int) in.readUnsignedLong(1));
                        var reductionId = (int) in.readUnsignedVarint();
                        fields.add(
                                new Field<>(
                                        fieldName,
                                        fieldDescription,
                                        innerLazyType,
                                        Function.identity(),
                                        embeddingType,
                                        reductionId));
                    }
                    var reductionId = (int) in.readUnsignedVarint();
                    return (StructType<Map<String, Object>, Map<String, Object>>)
                            in.getTypeCollection()
                                    .addType(
                                            new StructType<>(
                                                    id,
                                                    name,
                                                    description,
                                                    (List<Field<Map<String, Object>, ?, ?>>)
                                                            (List) fields,
                                                    r -> r,
                                                    reductionId));
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
                + "StructType "
                + getId()
                + " "
                + getName()
                + ": "
                + getDescription()
                + " {\n"
                + fields.stream()
                        .map(f -> indentStr + "  " + f.name() + ": " + f.description())
                        .collect(Collectors.joining(",\n"))
                + "\n"
                + indentStr
                + "}";
    }

    public Field<T, ?, ?> getField(String name) {
        return fieldMap.get(name);
    }

    public int size() {
        return fields.size();
    }

    public boolean hasField(String name) {
        return fieldMap.containsKey(name);
    }

    public List<String> getFieldNames() {
        return fields.stream().map(Field::name).collect(Collectors.toList());
    }

    public List<Field<T, ?, ?>> getFields() {
        return fields;
    }

    public int getReductionId() {
        return reductionId;
    }
}