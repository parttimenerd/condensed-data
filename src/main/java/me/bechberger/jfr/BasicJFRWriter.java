package me.bechberger.jfr;

import static me.bechberger.condensed.types.TypeCollection.normalize;

import java.nio.charset.Charset;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.FloatType.Type;
import me.bechberger.condensed.types.StructType.Field;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writes JFR events to a {@link CondensedOutputStream} which can be read by {@link BasicJFRReader}
 *
 * <p>Format of the output:
 *
 * <ul>
 *   <li>First message is the {@link Configuration} of the writer
 *   <li>Second message is the {@link Universe} of the writer
 *   <li>Every following message is a {@link RecordedEvent} with the corresponding {@link
 *       StructType} for the event
 * </ul>
 */
public class BasicJFRWriter {

    // optimization ideas:
    // - every "JVM: Flag" flag is only emitted once, as there are change events
    // - every event with period beginChunk or endChunk is stored by reference
    // - timspan and timestamp are stored as varints
    // TODO: test compression, test that only one Timestamp type is created
    // create simple file CLI to compress and read in files

    /**
     * Annotations of a field, only {@link jdk.jfr.ContentType} annotated annotations are considered
     * for equality
     */
    public static class Annotations {
        private final List<AnnotationElement> annotations;
        private final @Nullable AnnotationElement contentTypeAnnotation;
        private final boolean isUnsigned;

        public Annotations(List<AnnotationElement> annotations) {
            this.annotations = annotations;
            this.contentTypeAnnotation =
                    annotations.stream()
                            .filter(a -> isContentTypeAnnotation(a.getTypeName()))
                            .findFirst()
                            .orElse(null);
            this.isUnsigned =
                    annotations.stream().anyMatch(a -> a.getTypeName().equals("jdk.jfr.Unsigned"));
        }

        private static Map<String, Boolean> isAnnotationContentTypeAnnotation =
                new ConcurrentHashMap<>();

        public boolean isUnsigned() {
            return isUnsigned;
        }

        public @Nullable AnnotationElement getAnnotation(String name) {
            return annotations.stream()
                    .filter(a -> a.getTypeName().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        private static boolean isContentTypeAnnotation(String typeName) {
            return isAnnotationContentTypeAnnotation.computeIfAbsent(
                    typeName,
                    t -> {
                        try {
                            return Class.forName(t).getAnnotation(jdk.jfr.ContentType.class)
                                    != null;
                        } catch (ClassNotFoundException e) {
                            return false;
                        }
                    });
        }

        public @Nullable AnnotationElement getContentTypeAnnotation() {
            return annotations.stream()
                    .filter(a -> isContentTypeAnnotation(a.getTypeName()))
                    .findFirst()
                    .orElse(null);
        }

        public @Nullable String getContentType() {
            return contentTypeAnnotation != null ? contentTypeAnnotation.getTypeName() : null;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || obj.getClass() != Annotations.class) {
                return false;
            }
            var other = (Annotations) obj;
            return Objects.equals(getContentType(), other.getContentType())
                    && isUnsigned == other.isUnsigned;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getContentType(), isUnsigned);
        }
    }

    record TypeIdent(
            String typeName, String contentType, Annotations annotations, boolean isArray) {
        static TypeIdent of(ValueDescriptor field) {
            return of(field, field.isArray());
        }

        static TypeIdent of(ValueDescriptor field, boolean isArray) {
            return new TypeIdent(
                    field.getTypeName(),
                    field.getContentType(),
                    new Annotations(field.getAnnotationElements()),
                    isArray);
        }

        @Override
        public String toString() {
            return typeName
                    + (contentType != null ? "(" + contentType + ")" : "")
                    + (isArray ? "[]" : "");
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof TypeIdent ident
                    && typeName.equals(ident.typeName)
                    && Objects.equals(contentType, ident.contentType)
                    && isArray == ident.isArray
                    && annotations.equals(ident.annotations);
        }

        @Override
        public int hashCode() {
            return Objects.hash(typeName, contentType, annotations, isArray);
        }
    }

    /** all durations with more than 1 year are stored as 1 year, same with negative durations */
    private static final long MAX_DURATION_SECONDS = 60 * 60 * 24 * 365;

    private final CondensedOutputStream out;
    private final Configuration configuration;
    private final Map<EventType, StructType<RecordedEvent, Map<String, Object>>> eventTypeMap;
    private final Map<TypeIdent, CondensedType<?, ?>> fieldTypes;
    private final List<Entry<TypeIdent, CondensedType<?, ?>>> fieldTypesToAdd;
    private long lastStartTimeNanos = -1;
    private Map<Long, VarIntType> timespanTypePerDivisor = new HashMap<>();
    private VarIntType timeStampType;
    private Map<String, FloatType> memoryFloatTypes = new HashMap<>();
    private Universe universe = new Universe();
    private boolean wroteConfiguration = false;
    private CondensedType<Universe, Universe> universeType;

    /** field types that are not yet added, but their creation code is running + id */
    private final Map<TypeIdent, Integer> fieldTypesCurrentlyAdding;

    public BasicJFRWriter(CondensedOutputStream out, Configuration configuration) {
        this.out = out;
        this.configuration = configuration;
        this.eventTypeMap = new HashMap<>();
        this.fieldTypes = new HashMap<>();
        this.fieldTypesToAdd = new ArrayList<>();
        this.fieldTypesCurrentlyAdding = new HashMap<>();
        timeStampType =
                out.writeAndStoreType(
                        id ->
                                new VarIntType(
                                        id,
                                        "timestamp",
                                        "",
                                        false,
                                        configuration.timeStampTicksPerSecond()));
        // runtime shutdown hook to close the output stream
        Runtime.getRuntime().addShutdownHook(new Thread(out::close));
    }

    public BasicJFRWriter(CondensedOutputStream out) {
        this(out, Configuration.REASONABLE_DEFAULT);
    }

    private @Nullable CondensedType<?, ?> getTypeOrNull(TypeIdent name) {
        if (fieldTypes.containsKey(name)) {
            return fieldTypes.get(name);
        }
        var finding = fieldTypesToAdd.stream().filter(e -> e.getKey().equals(name)).findFirst();
        return finding.<CondensedType<?, ?>>map(Entry::getValue).orElse(null);
    }

    private CondensedType<?, ?> getTypeOrElse(
            TypeIdent name, Function<TypeIdent, CondensedType<?, ?>> creator) {
        var type = getTypeOrNull(name);
        if (type != null) {
            return type;
        }
        type = creator.apply(name);
        fieldTypesToAdd.add(Map.entry(name, type));
        return type;
    }

    private void processFieldTypesToAdd() {
        for (var entry : fieldTypesToAdd) {
            fieldTypes.put(entry.getKey(), entry.getValue());
        }
        fieldTypesToAdd.clear();
    }

    private CondensedType<?, ?> createTypeAndRegister(ValueDescriptor field, boolean isArray) {
        return out.writeAndStoreType(
                id -> {
                    fieldTypesCurrentlyAdding.put(TypeIdent.of(field), id);
                    var t = createType(field, isArray, id);
                    fieldTypesCurrentlyAdding.remove(TypeIdent.of(field));
                    return t;
                });
    }

    @NotNull
    private CondensedType<?, ?> createType(ValueDescriptor field, boolean isArray, Integer id) {
        if (isArray) {
            return createArrayType(field, id);
        }
        if (!field.getFields().isEmpty()) {
            // create a struct type
            return createStructType(field, id);
        }
        return createPrimitiveType(field, id);
    }

    @NotNull
    private CondensedType<?, ?> createPrimitiveType(ValueDescriptor field, Integer id) {
        Annotations annotations = new Annotations(field.getAnnotationElements());
        boolean isUnsigned = annotations.isUnsigned();
        AnnotationElement contentTypeAnnotation = annotations.getContentTypeAnnotation();
        String name =
                contentTypeAnnotation != null
                        ? contentTypeAnnotation.getTypeName()
                        : field.getTypeName();
        return switch (field.getTypeName()) {
            case "byte" -> new IntType(id, name, "", 1, !isUnsigned, OverflowMode.ERROR);
            case "short" -> new IntType(id, name, "", 2, !isUnsigned, OverflowMode.ERROR);
            case "char" -> new IntType(id, name, "", 2, false, OverflowMode.ERROR);
            case "boolean" -> new BooleanType(id, name, "");
            case "long" -> new VarIntType(id, name, "", !isUnsigned);
            case "int" -> new VarIntType(id, name, "", !isUnsigned);
            case "java.lang.String" -> {
                var s = new StringType(id, name, "", Charset.defaultCharset().toString());
                yield s;
            }
            case "float", "double" -> new FloatType(id, name, "");
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported type: "
                                    + name
                                    + ", content type: "
                                    + field.getContentType());
        };
    }

    private EmbeddingType getEmbeddingType(ValueDescriptor field) {
        if (field.getTypeName().equals("java.lang.String")) {
            return EmbeddingType.REFERENCE_PER_TYPE;
        }
        return field.getFields().isEmpty() || field.getTypeName().equals("jdk.jfr.StackFrame")
                ? EmbeddingType.INLINE
                : EmbeddingType.REFERENCE;
    }

    @NotNull
    private ArrayType<?, ?> createArrayType(ValueDescriptor field, Integer id) {
        EmbeddingType embedding = getEmbeddingType(field);
        TypeIdent innerIdent = TypeIdent.of(field, false);
        var name = field.getTypeName() + "[]";
        var innerType = getTypeOrNull(innerIdent);
        if (innerType != null) {
            return new ArrayType<>(id, name, "", innerType, embedding);
        }
        Integer currentId = fieldTypesCurrentlyAdding.get(innerIdent);
        if (currentId != null) {
            return new ArrayType<>(
                    id,
                    name,
                    "",
                    new LazyType<>(
                            currentId,
                            () ->
                                    Objects.requireNonNull(
                                            getTypeOrNull(innerIdent),
                                            "Type " + innerIdent + " not found in caches"),
                            innerIdent.toString()),
                    embedding);
        }
        return new ArrayType<>(id, name, "", createTypeAndRegister(field, false), embedding);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @NotNull
    private StructType<RecordedObject, Map<String, Object>> createStructType(
            ValueDescriptor field, Integer id) {
        var fields =
                field.getFields().stream()
                        .filter(f -> !f.getTypeName().equals(field.getTypeName()))
                        .map(e -> eventFieldToField(e, false))
                        .toList();
        var name = field.getTypeName();
        var description =
                field.getLabel()
                        + (field.getDescription() == null ? "" : ": " + field.getDescription());
        return new StructType<>(
                id,
                name,
                description,
                (List<Field<RecordedObject, ?, ?>>) (List) fields,
                members -> members);
    }

    private CondensedType<?, ?> getTypeCached(ValueDescriptor field) {
        return getTypeOrElse(
                TypeIdent.of(field), f -> createTypeAndRegister(field, field.isArray()));
    }

    @SuppressWarnings("unchecked")
    private <T extends RecordedObject> Field<T, ?, ?> eventFieldToField(
            ValueDescriptor field, boolean topLevel) {
        String description =
                field.getLabel()
                        + (field.getDescription() == null ? "" : ": " + field.getDescription());
        EmbeddingType embedding = getEmbeddingType(field);
        var getterAndCachedType = gettObjectFunction(field, topLevel);
        var getter = (Function<T, Object>) getterAndCachedType.getter;
        var ident = TypeIdent.of(field, false);
        var cachedType = getterAndCachedType.cachedType;
        if (cachedType != null) {
            return new Field<>(field.getName(), description, cachedType, getter, embedding);
        }
        Integer currentId = fieldTypesCurrentlyAdding.get(ident);
        if (currentId != null) {
            return new Field<>(
                    field.getName(),
                    description,
                    new LazyType<>(
                            currentId,
                            () ->
                                    Objects.requireNonNull(
                                            getTypeOrNull(ident),
                                            "Type " + ident + " not found in caches"),
                            ident.toString()),
                    getter,
                    embedding);
        }
        return new Field<>(field.getName(), description, getTypeCached(field), getter, embedding);
    }

    private record GetterAndCachedType(
            Function<RecordedObject, Object> getter, @Nullable CondensedType<?, ?> cachedType) {}

    private Optional<AnnotationElement> getAnnotationElement(ValueDescriptor field, String name) {
        return field.getAnnotationElements().stream()
                .filter(a -> a.getTypeName().equals(name))
                .findFirst();
    }

    private Optional<String> getDataAmountAnnotationValue(ValueDescriptor field) {
        return getAnnotationElement(field, "jdk.jfr.DataAmount")
                .map(a -> (String) a.getValue("value"));
    }

    private GetterAndCachedType gettObjectFunction(ValueDescriptor field, boolean topLevel) {
        String contentType = field.getContentType();
        if (contentType != null && contentType.equals("jdk.jfr.Timestamp")) {
            return new GetterAndCachedType(
                    event -> {
                        var instant = event.getInstant("startTime");
                        long l = instant.getEpochSecond() * 1_000_000_000 + instant.getNano();
                        long r = l - lastStartTimeNanos;
                        lastStartTimeNanos = l;
                        return Math.round(
                                r / (1_000_000_000.0 / configuration.timeStampTicksPerSecond()));
                    },
                    timeStampType);
        }
        if (contentType != null && contentType.equals("jdk.jfr.Timespan")) {
            return getTimespanType(field, topLevel);
        }
        if (field.getTypeName().equals("long") && configuration.memoryAsBFloat16()) {
            var dataAmount = getDataAmountAnnotationValue(field);
            if (dataAmount.isPresent()) {
                return new GetterAndCachedType(
                        event -> (float) event.getLong(field.getName()),
                        getMemoryFloatType(dataAmount.get()));
            }
        }
        return new GetterAndCachedType(
                event -> normalize(event.getValue(field.getName())),
                getTypeOrNull(TypeIdent.of(field)));
    }

    private FloatType getMemoryFloatType(String kind) {
        return memoryFloatTypes.computeIfAbsent(
                kind,
                k ->
                        out.writeAndStoreType(
                                id -> new FloatType(id, "memory " + kind, "", Type.BFLOAT16)));
    }

    private VarIntType getCachedTimespanType(long divisor) {
        return timespanTypePerDivisor.computeIfAbsent(
                divisor,
                d -> out.writeAndStoreType(id -> new VarIntType(id, "timespan", "", true, d)));
    }

    private GetterAndCachedType getTimespanType(ValueDescriptor field, boolean topLevel) {
        long specifiedTicksPerSec = getSpecifiedTicksPerSec(field);
        long ticksPerSec =
                Math.min(
                        specifiedTicksPerSec,
                        field.getName().equals("duration") && topLevel
                                ? configuration.timeStampTicksPerSecond()
                                : configuration.durationTicksPerSecond());
        return new GetterAndCachedType(
                event -> {
                    if (event.getDuration(field.getName()).getSeconds() < -MAX_DURATION_SECONDS) {
                        return -MAX_DURATION_SECONDS * ticksPerSec;
                    } else if (event.getDuration(field.getName()).getSeconds()
                            > MAX_DURATION_SECONDS) {
                        return MAX_DURATION_SECONDS * ticksPerSec;
                    }
                    var val = event.getDuration(field.getName()).toNanos();
                    return val;
                },
                getCachedTimespanType(1_000_000_000 / ticksPerSec));
    }

    private static long getSpecifiedTicksPerSec(ValueDescriptor field) {
        String ticksAnnotationValue =
                (String)
                        field.getAnnotationElements().stream()
                                .filter(a -> a.getTypeName().equals("jdk.jfr.Timespan"))
                                .findFirst()
                                .map(a -> a.getValue("value"))
                                .orElse(Timespan.NANOSECONDS);
        long specifiedTicksPerSec =
                switch (ticksAnnotationValue) {
                    case Timespan.NANOSECONDS, Timespan.TICKS -> 1_000_000_000;
                    case Timespan.MICROSECONDS -> 1_000_000;
                    case Timespan.MILLISECONDS -> 1_000;
                    case Timespan.SECONDS -> 1;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported timespan: " + ticksAnnotationValue);
                };
        return specifiedTicksPerSec;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    StructType<RecordedEvent, Map<String, Object>> createAndRegisterEventStructType(
            EventType eventType) {
        return out.writeAndStoreType(
                id -> {
                    var fields =
                            eventType.getFields().stream()
                                    .map(e -> this.<RecordedEvent>eventFieldToField(e, true))
                                    .toList();
                    return new StructType<>(
                            id,
                            eventType.getName(),
                            eventType.getLabel()
                                    + (eventType.getDescription() == null
                                            ? ""
                                            : ": " + eventType.getDescription()),
                            (List<Field<RecordedEvent, ?, ?>>) (List) fields,
                            members -> members);
                });
    }

    private void writeConfiguration() {
        var t = Configuration.createType(out.getTypeCollection());
        out.writeType(t);
        out.writeMessage(t, configuration);
    }

    private void writeUniverse() {
        if (universeType == null) {
            universeType = universe.getStructType(out.getTypeCollection());
            out.writeType(universeType);
        }
        out.writeMessage(universeType, universe);
    }

    public void processEvent(RecordedEvent event) {
        if (!wroteConfiguration) {
            writeConfiguration();
            wroteConfiguration = true;
            universe.setStartTimeNanos(
                    event.getStartTime().getEpochSecond() * 1_000_000_000
                            + event.getStartTime().getNano());
            writeUniverse();
        }
        if (out.isClosed()) {
            return;
        }
        var type =
                eventTypeMap.computeIfAbsent(
                        event.getEventType(), this::createAndRegisterEventStructType);
        processFieldTypesToAdd();
        out.writeMessage(type, event);
    }
}
