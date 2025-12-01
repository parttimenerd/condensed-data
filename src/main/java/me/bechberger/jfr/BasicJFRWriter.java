package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;
import static me.bechberger.condensed.types.TypeCollection.normalize;
import static me.bechberger.jfr.ReducedJFRTypes.REDUCED_JFR_TYPES;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import jdk.jfr.AnnotationElement;
import jdk.jfr.EventType;
import jdk.jfr.Timespan;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.*;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.stats.Statistic;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.FloatType.Type;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.JFRReduction.ReducedStackTrace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;

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

        private static final Map<String, Boolean> isAnnotationContentTypeAnnotation =
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

    private final CondensedOutputStream out;
    private final Configuration configuration;
    private final Map<EventType, StructType<RecordedEvent, Map<String, Object>>> eventTypeMap;
    private final Map<TypeIdent, CondensedType<?, ?>> fieldTypes;
    private final List<Entry<TypeIdent, CondensedType<?, ?>>> fieldTypesToAdd;
    private final Map<Long, VarIntType> timespanTypePerDivisor = new HashMap<>();
    private final VarIntType timeStampType;
    private final Map<String, FloatType> memoryFloatTypes = new HashMap<>();
    private final Map<String, VarIntType> memoryVarIntTypes = new HashMap<>();
    private StructType<?, ?> reducedStackTraceType;
    Universe universe = new Universe();
    private boolean wroteConfiguration = false;
    private CondensedType<Universe, Universe> universeType;
    private final JFREventCombiner eventCombiner;
    private final EventDeduplication deduplication;
    private volatile boolean closed = false;
    private long defaultStartTimeNanos = System.currentTimeMillis() * 1000000;

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
        out.setReductions(new JFRReduction.JFRReductions(configuration, universe));
        out.setHashAndEqualsConfig(
                configuration.useSpecificHashesAndRefs()
                        ? new JFRHashConfig()
                        : HashAndEqualsConfig.NONE);

        eventCombiner = new JFREventCombiner(out, configuration, this);
        deduplication = new JFREventDeduplication(configuration);
    }

    public BasicJFRWriter(CondensedOutputStream out) {
        this(out, Configuration.REASONABLE_DEFAULT);
    }

    JFREventCombiner getEventCombiner() {
        return eventCombiner;
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

    CondensedType<?, ?> createTypeAndRegister(ValueDescriptor field, boolean isArray) {
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
            case "long", "int" -> new VarIntType(id, name, "", !isUnsigned);
            case "java.lang.String" ->
                    new StringType(id, name, "", Charset.defaultCharset().toString());
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
        return createArrayType(field, id, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @NotNull
    private ArrayType<?, ?> createArrayType(ValueDescriptor field, Integer id, @Nullable Function<Integer, CondensedType<?, ?>> fieldType) {
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
        return new ArrayType<>(id, name, "", fieldType == null ?
                createTypeAndRegister(field, false) : (CondensedType)out.writeAndStoreType(fieldType), embedding);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @NotNull
    private StructType<RecordedObject, Map<String, Object>> createStructType(
            ValueDescriptor field, Integer id) {
        var fields = field.getFields().stream().map(e -> eventFieldToField(e, false)).toList();
        var name = field.getTypeName();
        if (REDUCED_JFR_TYPES.containsKey(name)) { // Remove fields based on configuration
            var removedFields = ReducedJFRTypes.getRemovedFields(name, configuration, false);
            fields = fields.stream()
                    .filter(f -> !removedFields.contains(f.name()))
                    .toList();
        }
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

    CondensedType<?, ?> getTypeCached(ValueDescriptor field) {
        return getTypeCached(field, field.isArray());
    }

    CondensedType<?, ?> getTypeCached(ValueDescriptor field, boolean isArray) {
        return getTypeOrElse(
                TypeIdent.of(field), f -> createTypeAndRegister(field, isArray));
    }

    public String getDescription(ValueDescriptor field) {
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
        JSONArray arr;
        try {
            arr = new JSONArray(description);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid description: " + description, e);
        }
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
    public <T extends RecordedObject> Field<T, ?, ?> eventFieldToField(
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
        if ((field.getTypeName().equals("long") || field.getTypeName().equals("int"))
                && configuration.memoryAsBFloat16()) {
            var dataAmount = getDataAmountAnnotationValue(field);
            if (dataAmount.isPresent()) {
                switch (field.getTypeName()) {
                    case "int" -> {
                        return new GetterAndCachedType(
                                event -> (float) event.getInt(field.getName()),
                                getMemoryFloatType(dataAmount.get()));
                    }
                    case "long" -> {
                        return new GetterAndCachedType(
                                event -> (float) event.getLong(field.getName()),
                                getMemoryFloatType(dataAmount.get()));
                    }
                }
            }
        } else {
            var dataAmount = getDataAmountAnnotationValue(field);
            if (dataAmount.isPresent()) {
                switch (field.getTypeName()) {
                    case "int" -> {
                        return new GetterAndCachedType(
                                event -> event.getInt(field.getName()),
                                getMemoryVarIntType(dataAmount.get()));
                    }
                    case "short" -> {
                        return new GetterAndCachedType(
                                event -> (short) event.getInt(field.getName()),
                                getMemoryVarIntType(dataAmount.get()));
                    }
                    case "byte" -> {
                        return new GetterAndCachedType(
                                event -> (byte) event.getInt(field.getName()),
                                getMemoryVarIntType(dataAmount.get()));
                    }
                    case "long" -> {
                        return new GetterAndCachedType(
                                event -> event.getLong(field.getName()),
                                getMemoryVarIntType(dataAmount.get()));
                    }
                }
            }
        }
        JFRReduction reduction = JFRReduction.NONE;
        if (field.getTypeName().equals("jdk.types.StackTrace")) {
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

    private ValueDescriptor getField(ValueDescriptor parent, String fieldName) {
        return parent.getFields().stream()
                .filter(f -> f.getName().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CondensedType<?, ?> createFrameType(int id, ValueDescriptor field) {
        ValueDescriptor methodField = getField(field, "method");
        ValueDescriptor typeField = getField(field, "type");
        ValueDescriptor bciField = getField(field, "bytecodeIndex");
        ValueDescriptor lineNumberField = getField(field, "lineNumber");

        List<Field<?, ?, ?>> fields = new ArrayList<>();
        fields.add(
                new Field<>(
                        "method",
                        getDescription(methodField),
                        getTypeCached(methodField),
                        obj -> ((RecordedFrame) obj).getMethod(),
                        EmbeddingType.REFERENCE));
        if (!configuration.removeBCIAndLineNumberFromStackFrames()) {
            fields.add(
                    new Field<>(
                            "bci",
                            getDescription(bciField),
                            getTypeCached(bciField),
                            obj -> ((RecordedFrame) obj).getBytecodeIndex(),
                            EmbeddingType.INLINE));
            fields.add(
                    new Field<>(
                            "lineNumber",
                            getDescription(lineNumberField),
                            getTypeCached(lineNumberField),
                            obj -> ((RecordedFrame) obj).getLineNumber(),
                            EmbeddingType.INLINE));
        }
        if (!configuration.removeTypeInformationFromStackFrames()) {
            fields.add(
                    new Field<>(
                            "type",
                            getDescription(typeField),
                            getTypeCached(typeField),
                            obj -> ((RecordedFrame) obj).getType(),
                            EmbeddingType.REFERENCE));
        }
        return new StructType(
                id,
                "jdk.types.StackFrame" + (fields.size() < 4 ? "_reduced" : ""),
                "Stack frame",
                fields,
                obj -> obj);
    }

    private StructType<?, ?> getReducedStackTraceType(ValueDescriptor field) {
        if (reducedStackTraceType == null) {
            var truncatedField = getField(field, "truncated");
            var framesField = getField(field, "frames");
            reducedStackTraceType =
                    out.writeAndStoreType(
                            id -> {
                                var arrayType =
                                        out.writeAndStoreType(
                                                innerId -> createArrayType(framesField, innerId));
                                return new StructType<>(
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

    private VarIntType getMemoryVarIntType(String kind) {
        return memoryVarIntTypes.computeIfAbsent(
                kind,
                k ->
                        out.writeAndStoreType(
                                id -> new VarIntType(id, "memory varint " + kind, "", true)));
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
                event -> event.getDuration(field.getName()),
                getCachedTimespanType(1_000_000_000 / ticksPerSec),
                JFRReduction.TIMESPAN_REDUCTION);
    }

    VarIntType getDurationType() {
        long ticksPerSec = configuration.timeStampTicksPerSecond();
        return getCachedTimespanType(1_000_000_000 / ticksPerSec);
    }

    boolean isEffectivelyZeroDuration(long ticks) {
        long ticksPerSec = configuration.durationTicksPerSecond();
        long multiplier = 1_000_000_000 / ticksPerSec;
        return ticks < multiplier && ticks > -multiplier;
    }

    private static long getSpecifiedTicksPerSec(ValueDescriptor field) {
        String ticksAnnotationValue =
                (String)
                        field.getAnnotationElements().stream()
                                .filter(a -> a.getTypeName().equals("jdk.jfr.Timespan"))
                                .findFirst()
                                .map(a -> a.getValue("value"))
                                .orElse(Timespan.NANOSECONDS);
        return switch (ticksAnnotationValue) {
            case Timespan.NANOSECONDS, Timespan.TICKS -> 1_000_000_000;
            case Timespan.MICROSECONDS -> 1_000_000;
            case Timespan.MILLISECONDS -> 1_000;
            case Timespan.SECONDS -> 1;
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported timespan: " + ticksAnnotationValue);
        };
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

    @SuppressWarnings({"rawtypes", "unchecked"})
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
        return switch (event.getEventType().getName()) {
            case "jdk.G1HeapRegionTypeChange" ->
                    // from == to
                    event.getString("from").equals(event.getString("to"));
            case "jdk.MetaspaceChunkFreeListSummary" ->
                    // all zero
                    event.getLong("specializedChunks") == 0
                            && event.getLong("specializedChunksTotalSize") == 0
                            && event.getLong("smallChunks") == 0
                            && event.getLong("smallChunksTotalSize") == 0
                            && event.getLong("mediumChunks") == 0
                            && event.getLong("mediumChunksTotalSize") == 0
                            && event.getLong("humongousChunks") == 0
                            && event.getLong("humongousChunksTotalSize") == 0;
            default -> false;
        };
    }

    /** Checks if the event should be ignored */
    private boolean ignoreEvent(RecordedEvent event) {
        if (configuration.ignoreUnnecessaryEvents()) {
            return isUnnecessaryEvent(event) || deduplication.recordAndCheckIfDuplicate(event);
        }
        return false;
    }

    public void writeConfigurationAndUniverseIfNeeded(long startTimeNanos) {
        if (!wroteConfiguration) {
            writeConfiguration();
            wroteConfiguration = true;
            universe.setStartTimeNanos(startTimeNanos);
            universe.setLastStartTimeNanos(universe.getStartTimeNanos());
            writeUniverse();
        }
    }

    public void processEvent(RecordedEvent event) {
        if (ignoreEvent(event)) {
            return;
        }
        writeConfigurationAndUniverseIfNeeded(toNanoSeconds(event.getStartTime()));
        if (out.isClosed()) {
            return;
        }
        var type =
                eventTypeMap.computeIfAbsent(
                        event.getEventType(), this::createAndRegisterEventStructType);
        processFieldTypesToAdd();

        if (eventCombiner.processEvent(event)) {
            return;
        }
        out.writeMessage(type, event);
    }

    /**
     * Writes the {@link StructType} for the event type to the underlying output if it is not
     * already written
     */
    public void writeOutEventTypeIfNeeded(EventType eventType) {
        eventTypeMap.computeIfAbsent(eventType, this::createAndRegisterEventStructType);
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
        close();
    }

    /** Be sure to close the output stream after writing all events */
    public void processJFRFile(Path file) {
        try (var r = new RecordingFile(file)) {
            processJFRFile(r);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        writeConfigurationAndUniverseIfNeeded(defaultStartTimeNanos); // ensure universe is written
        closed = true;
        eventCombiner.close();
        out.close();
    }

    public boolean isClosed() {
        return closed;
    }

    public int estimateSize() {
        return out.estimateSize();
    }

    public Duration getDuration() {
        return universe.getDuration();
    }

    public EventDeduplication getDeduplication() {
        return deduplication;
    }

    public Statistic getUncompressedStatistic() {
        return out.getStatistics();
    }

    public CondensedOutputStream getOutputStream() {
        return out;
    }
}