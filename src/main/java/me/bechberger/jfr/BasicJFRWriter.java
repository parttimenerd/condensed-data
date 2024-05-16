package me.bechberger.jfr;

import static me.bechberger.condensed.types.TypeCollection.normalize;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.FloatType.Type;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.JFRReduction.ReducedStackTrace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;

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
    private Map<Long, VarIntType> timespanTypePerDivisor = new HashMap<>();
    private VarIntType timeStampType;
    private Map<String, FloatType> memoryFloatTypes = new HashMap<>();
    private StructType<?, ?> reducedStackTraceType;
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
        timeStampType = out.writeAndStoreType(id -> new VarIntType(id, "timestamp", "", false, 1));
        // runtime shutdown hook to close the output stream
        Runtime.getRuntime().addShutdownHook(new Thread(out::close));
        out.setReductions(new JFRReduction.JFRReductions(configuration, universe));
        out.setHashAndEqualsConfig(
                configuration.useSpecificHashesAndRefs()
                        ? new JFRHashConfig()
                        : HashAndEqualsConfig.NONE);
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

    @NotNull
    private ArrayType<?, ?> createArrayType(ValueDescriptor field, Integer id) {
        EmbeddingType embedding = JFRHashConfig.getEmbeddingType(field);
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
        var fields = field.getFields().stream().map(e -> eventFieldToField(e, false)).toList();
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

    private String getDescription(ValueDescriptor field) {
        // encode all important info in the description
        JSONArray arr = new JSONArray();
        arr.put(field.getTypeName());
        arr.put(field.getContentType());
        arr.put(
                field.getAnnotationElements().stream()
                        .map(
                                a -> {
                                    JSONArray annotation = new JSONArray();
                                    annotation.put(a.getTypeName());
                                    annotation.put(a.getValues());
                                    return annotation;
                                })
                        .toList());
        arr.put(field.getLabel());
        arr.put(field.getDescription());
        arr.put(field.isArray());
        return arr.toString();
    }

    record ParsedAnnotationElement(String type, List<Object> values) {}

    public record ParsedFieldDescription(
            String type,
            String contentType,
            List<ParsedAnnotationElement> annotations,
            String label,
            String description,
            boolean isArray) {}

    @SuppressWarnings("unchecked")
    public static ParsedFieldDescription parseFieldDescription(String description) {
        JSONArray arr = new JSONArray(description);
        return new ParsedFieldDescription(
                arr.getString(0),
                arr.isNull(1) ? null : arr.getString(1),
                arr.getJSONArray(2).toList().stream()
                        .map(
                                o -> {
                                    var a = (List<Object>) o;
                                    return new ParsedAnnotationElement(
                                            (String) a.get(0), (List<Object>) a.get(1));
                                })
                        .toList(),
                arr.isNull(3) ? null : arr.getString(3),
                arr.isNull(4) ? null : arr.getString(4),
                arr.getBoolean(5));
    }

    @SuppressWarnings("unchecked")
    private <T extends RecordedObject> Field<T, ?, ?> eventFieldToField(
            ValueDescriptor field, boolean topLevel) {
        String description = getDescription(field);
        EmbeddingType embedding = JFRHashConfig.getEmbeddingType(field);
        var getterAndCachedType = gettObjectFunction(field, topLevel);
        var getter = (Function<T, Object>) getterAndCachedType.getter;
        var ident = TypeIdent.of(field, false);
        var cachedType = getterAndCachedType.cachedType;
        var reductionId = getterAndCachedType.reduction.ordinal();
        if (cachedType != null) {
            return new Field<>(
                    field.getName(), description, cachedType, getter, embedding, reductionId);
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
                    embedding,
                    reductionId);
        }
        return new Field<>(
                field.getName(), description, getTypeCached(field), getter, embedding, reductionId);
    }

    private record GetterAndCachedType(
            Function<RecordedObject, Object> getter,
            @Nullable CondensedType<?, ?> cachedType,
            JFRReduction reduction) {
        GetterAndCachedType(
                Function<RecordedObject, Object> getter, @Nullable CondensedType<?, ?> cachedType) {
            this(getter, cachedType, JFRReduction.NONE);
        }
    }

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
                    event -> event.getInstant(field.getName()),
                    timeStampType,
                    JFRReduction.TIMESTAMP_REDUCTION);
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
        JFRReduction reduction = JFRReduction.NONE;
        if (field.getTypeName().equals("jdk.types.StackTrace")
                && configuration.maxStackTraceDepth() != -1) {
            return new GetterAndCachedType(
                    event -> {
                        var trace = event.getValue(field.getName());
                        // doing this in reductions is wasteful
                        return trace == null
                                ? null
                                : ReducedStackTrace.create(
                                        (RecordedStackTrace) trace,
                                        (int) configuration.maxStackTraceDepth());
                    },
                    getReducedStackTraceType(field));
        }
        return new GetterAndCachedType(
                event -> normalize(event.getValue(field.getName())),
                getTypeOrNull(TypeIdent.of(field)),
                reduction);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StructType<?, ?> getReducedStackTraceType(ValueDescriptor field) {
        if (reducedStackTraceType == null) {
            var truncatedField =
                    field.getFields().stream()
                            .filter(f -> f.getName().equals("truncated"))
                            .findFirst()
                            .get();
            var framesField =
                    field.getFields().stream()
                            .filter(f -> f.getName().equals("frames"))
                            .findFirst()
                            .get();
            reducedStackTraceType =
                    out.writeAndStoreType(
                            id -> {
                                var arrayType =
                                        out.writeAndStoreType(
                                                innerId -> createArrayType(framesField, innerId));
                                return new StructType<ReducedStackTrace, ReadStruct>(
                                        id,
                                        "jdk.types.StackTrace",
                                        "Reduced stack trace",
                                        List.of(
                                                new Field<>(
                                                        "truncated",
                                                        getDescription(truncatedField),
                                                        TypeCollection.getDefaultTypeInstance(
                                                                BooleanType.SPECIFIED_TYPE),
                                                        ReducedStackTrace::isTruncated,
                                                        EmbeddingType.INLINE),
                                                new Field<>(
                                                        "frames",
                                                        getDescription(framesField),
                                                        arrayType,
                                                        ReducedStackTrace::getFrames,
                                                        EmbeddingType.INLINE)),
                                        obj -> obj);
                            });
        }
        return reducedStackTraceType;
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

    String getEventDescription(EventType type) {
        var arr = new JSONArray();
        arr.put(type.getLabel());
        arr.put(type.getDescription());
        return arr.toString();
    }

    public record ParsedEventDescription(String label, String description) {}

    public static ParsedEventDescription parseEventDescription(String description) {
        JSONArray arr = new JSONArray(description);
        return new ParsedEventDescription(arr.getString(0), arr.getString(1));
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
                            getEventDescription(eventType),
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

    private boolean isUnnecessaryEvent(RecordedEvent event) {
        switch (event.getEventType().getName()) {
            case "jdk.G1HeapRegionTypeChange":
                // from == to
                return event.getString("from").equals(event.getString("to"));
            default:
                return false;
        }
    }

    /** Checks if the event should be ignored */
    private boolean ignoreEvent(RecordedEvent event) {
        if (configuration.ignoreUnnecessaryEvents()) {
            return isUnnecessaryEvent(event);
        }
        return false;
    }

    public void processEvent(RecordedEvent event) {
        if (ignoreEvent(event)) {
            return;
        }
        if (!wroteConfiguration) {
            writeConfiguration();
            wroteConfiguration = true;
            universe.setStartTimeNanos(
                    event.getStartTime().getEpochSecond() * 1_000_000_000
                            + event.getStartTime().getNano());
            universe.setLastStartTimeNanos(universe.getStartTimeNanos());
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

    /** Be sure to close the output stream after writing all events */
    public void processJFRFile(RecordingFile r) {
        while (r.hasMoreEvents()) {
            try {
                processEvent(r.readEvent());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Be sure to close the output stream after writing all events */
    public void processJFRFile(Path file) {
        try (var r = new RecordingFile(file)) {
            processJFRFile(r);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
