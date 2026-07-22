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
import java.util.concurrent.atomic.AtomicBoolean;
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
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;
import me.bechberger.util.json.Util;
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
            var contentTypes =
                    annotations.stream()
                            .filter(a -> isContentTypeAnnotation(a.getTypeName()))
                            .toList();
            // @Unsigned is a content-type annotation but is the least specific one: fields like
            // OldObjectSample.allocationTime carry both @Unsigned and @Timestamp/@Timespan. We must
            // pick the specific annotation (Timestamp/Timespan/DataAmount/...) so tick->epoch and
            // unit conversions apply; otherwise the value is stored as a raw unsigned varint.
            this.contentTypeAnnotation =
                    contentTypes.stream()
                            .filter(a -> !a.getTypeName().equals("jdk.jfr.Unsigned"))
                            .findFirst()
                            .orElseGet(() -> contentTypes.stream().findFirst().orElse(null));
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

        public static boolean isContentTypeAnnotation(String typeName) {
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
            return contentTypeAnnotation;
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
    private final FooterCollector footerCollector;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final long defaultStartTimeNanos = System.currentTimeMillis() * 1000000;

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
        footerCollector = new FooterCollector(configuration.cpuBucketSeconds());
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
            case "float" -> new FloatType(id, name, "", Type.FLOAT32);
            case "double" -> new FloatType(id, name, "", getDoubleStorageType());
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported type: "
                                    + name
                                    + ", content type: "
                                    + field.getContentType());
        };
    }

    private Type getDoubleStorageType() {
        if (Configuration.REDUCED_DEFAULT.name().equals(configuration.name())) {
            return Type.FLOAT16;
        }
        if (configuration.memoryAsBFloat16()) {
            return Type.FLOAT32;
        }
        return Type.FLOAT64;
    }

    @NotNull
    private ArrayType<?, ?> createArrayType(ValueDescriptor field, Integer id) {
        return createArrayType(field, id, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @NotNull
    private ArrayType<?, ?> createArrayType(
            ValueDescriptor field,
            Integer id,
            @Nullable Function<Integer, CondensedType<?, ?>> fieldType) {
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
        return new ArrayType<>(
                id,
                name,
                "",
                fieldType == null
                        ? createTypeAndRegister(field, false)
                        : (CondensedType) out.writeAndStoreType(fieldType),
                embedding);
    }

    @NotNull
    private StructType<RecordedObject, Map<String, Object>> createStructType(
            ValueDescriptor field, Integer id) {
        List<Field<RecordedObject, ?, ?>> fields =
                field.getFields().stream()
                        .<Field<RecordedObject, ?, ?>>map(e -> eventFieldToField(e, false))
                        .collect(java.util.stream.Collectors.toList());
        var name = field.getTypeName();
        if (REDUCED_JFR_TYPES.containsKey(name)) { // Remove fields based on configuration
            var removedFields = ReducedJFRTypes.getRemovedFields(name, configuration, false);
            fields = fields.stream().filter(f -> !removedFields.contains(f.name())).toList();
        }
        var description =
                field.getLabel()
                        + (field.getDescription() == null ? "" : ": " + field.getDescription());
        return new StructType<>(id, name, description, fields, members -> members);
    }

    CondensedType<?, ?> getTypeCached(ValueDescriptor field) {
        return getTypeCached(field, field.isArray());
    }

    CondensedType<?, ?> getTypeCached(ValueDescriptor field, boolean isArray) {
        return getTypeOrElse(TypeIdent.of(field), f -> createTypeAndRegister(field, isArray));
    }

    public String getDescription(ValueDescriptor field) {
        // encode all important info in the description
        List<Object> arr = new ArrayList<>();
        arr.add(field.getTypeName());
        arr.add(field.getContentType());
        arr.add(
                new ArrayList<>(
                        field.getAnnotationElements().stream()
                                .map(
                                        a -> {
                                            List<Object> annotation = new ArrayList<>();
                                            annotation.add(a.getTypeName());
                                            annotation.add(new ArrayList<>(a.getValues()));
                                            return (Object) annotation;
                                        })
                                .toList()));
        arr.add(field.getLabel());
        arr.add(field.getDescription());
        arr.add(field.isArray());
        return PrettyPrinter.compactPrint(arr);
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
        List<Object> arr;
        try {
            arr = (List<Object>) JSONParser.parse(description);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid description: " + description, e);
        }
        return new ParsedFieldDescription(
                (String) arr.get(0),
                arr.get(1) == null ? null : (String) arr.get(1),
                ((List<Object>) arr.get(2))
                        .stream()
                                .map(
                                        o -> {
                                            var a = (List<Object>) o;
                                            return new ParsedAnnotationElement(
                                                    (String) a.get(0), (List<Object>) a.get(1));
                                        })
                                .toList(),
                arr.get(3) == null ? null : (String) arr.get(3),
                arr.get(4) == null ? null : (String) arr.get(4),
                (Boolean) arr.get(5));
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

    private @Nullable GetterAndCachedType getLosslessBytesDataAmountType(
            ValueDescriptor field, Optional<String> dataAmount) {
        if (dataAmount.isEmpty() || !"BYTES".equals(dataAmount.get())) {
            return null;
        }
        var varIntType = getMemoryVarIntType(dataAmount.get());
        return switch (field.getTypeName()) {
            case "byte", "short", "int" -> {
                var acc = UnsafeRecordedObjectAccessor.intField(field.getName(), 0);
                yield new GetterAndCachedType(
                        event -> (long) acc.get(event),
                        varIntType,
                        JFRReduction.DATA_AMOUNT_BYTES_REDUCTION);
            }
            case "long" -> {
                var acc = UnsafeRecordedObjectAccessor.longField(field.getName(), 0L);
                yield new GetterAndCachedType(
                        event -> acc.get(event),
                        varIntType,
                        JFRReduction.DATA_AMOUNT_BYTES_REDUCTION);
            }
            default -> null;
        };
    }

    private @Nullable GetterAndCachedType getBFloat16DataAmountType(
            ValueDescriptor field, Optional<String> dataAmount) {
        if (!configuration.memoryAsBFloat16() || dataAmount.isEmpty()) {
            return null;
        }
        return switch (field.getTypeName()) {
            case "int" -> {
                var acc = UnsafeRecordedObjectAccessor.intField(field.getName(), 0);
                yield new GetterAndCachedType(
                        event -> (float) acc.get(event), getMemoryFloatType(dataAmount.get()));
            }
            case "long" -> {
                var acc = UnsafeRecordedObjectAccessor.longField(field.getName(), 0L);
                yield new GetterAndCachedType(
                        event -> (float) acc.get(event), getMemoryFloatType(dataAmount.get()));
            }
            default -> null;
        };
    }

    private @Nullable GetterAndCachedType getVarIntDataAmountType(
            ValueDescriptor field, Optional<String> dataAmount) {
        if (dataAmount.isEmpty()) {
            return null;
        }
        var varIntType = getMemoryVarIntType(dataAmount.get());
        return switch (field.getTypeName()) {
            case "int" -> {
                var acc = UnsafeRecordedObjectAccessor.intField(field.getName(), 0);
                yield new GetterAndCachedType(event -> acc.get(event), varIntType);
            }
            case "short" -> {
                var acc = UnsafeRecordedObjectAccessor.intField(field.getName(), 0);
                yield new GetterAndCachedType(event -> (short) acc.get(event), varIntType);
            }
            case "byte" -> {
                var acc = UnsafeRecordedObjectAccessor.intField(field.getName(), 0);
                yield new GetterAndCachedType(event -> (byte) acc.get(event), varIntType);
            }
            case "long" -> {
                var acc = UnsafeRecordedObjectAccessor.longField(field.getName(), 0L);
                yield new GetterAndCachedType(event -> acc.get(event), varIntType);
            }
            default -> null;
        };
    }

    private static Object getDefaultValueForMissingField(ValueDescriptor field) {
        return switch (field.getTypeName()) {
            case "boolean" -> false;
            case "byte" -> (byte) 0;
            case "char" -> (char) 0;
            case "short" -> (short) 0;
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0.0f;
            case "double" -> 0.0d;
            default -> null;
        };
    }

    private static Object getValueOrDefault(
            RecordedObject event, ValueDescriptor field, Function<RecordedObject, Object> getter) {
        try {
            return getter.apply(event);
        } catch (IllegalArgumentException e) {
            // Field missing due to schema evolution between JFR chunks
            return getDefaultValueForMissingField(field);
        }
    }

    private GetterAndCachedType gettObjectFunction(ValueDescriptor field, boolean topLevel) {
        // JMC's ValueDescriptor.getContentType() returns null when a field carries more than one
        // content-type annotation (e.g. @Unsigned + @Timestamp on OldObjectSample.allocationTime),
        // which would drop the tick->epoch conversion. Resolve via our own Annotations, which
        // deprioritizes @Unsigned in favor of the specific content type.
        String contentType = new Annotations(field.getAnnotationElements()).getContentType();
        if (contentType != null && contentType.equals("jdk.jfr.Timestamp")) {
            return new GetterAndCachedType(
                    event -> getValueOrDefault(event, field, e -> e.getInstant(field.getName())),
                    timeStampType,
                    JFRReduction.TIMESTAMP_REDUCTION);
        }
        // Fallback: newer JDK JFR files may have null contentType / missing annotations.
        // Use RecordedEvent.getStartTime() which works regardless of annotations.
        if (contentType == null
                && field.getName().equals("startTime")
                && topLevel
                && field.getTypeName().equals("long")) {
            return new GetterAndCachedType(
                    event -> ((RecordedEvent) event).getStartTime(),
                    timeStampType,
                    JFRReduction.TIMESTAMP_REDUCTION);
        }
        if (contentType != null && contentType.equals("jdk.jfr.Timespan")) {
            return getTimespanType(field, topLevel);
        }
        // Fallback: duration field without @Timespan annotation
        if (contentType == null
                && field.getName().equals("duration")
                && topLevel
                && field.getTypeName().equals("long")) {
            return getTimespanType(field, topLevel);
        }
        // BFloat16 for @Percentage floats (e.g., ThreadCPULoad, CPULoad)
        if (configuration.memoryAsBFloat16()
                && field.getTypeName().equals("float")
                && contentType != null
                && contentType.equals("jdk.jfr.Percentage")) {
            return new GetterAndCachedType(
                    event -> getValueOrDefault(event, field, e -> e.getFloat(field.getName())),
                    getPercentageFloatType());
        }
        var dataAmount = getDataAmountAnnotationValue(field);
        var losslessBytesType = getLosslessBytesDataAmountType(field, dataAmount);
        if (losslessBytesType != null) {
            return losslessBytesType;
        }
        var bFloat16DataAmountType = getBFloat16DataAmountType(field, dataAmount);
        if (bFloat16DataAmountType != null) {
            return bFloat16DataAmountType;
        }
        var varIntDataAmountType = getVarIntDataAmountType(field, dataAmount);
        if (varIntDataAmountType != null) {
            return varIntDataAmountType;
        }
        JFRReduction reduction = JFRReduction.NONE;
        if (field.getTypeName().equals("jdk.types.StackTrace")) {
            return new GetterAndCachedType(
                    event -> {
                        var trace =
                                getValueOrDefault(event, field, e -> e.getValue(field.getName()));
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
                event ->
                        normalize(
                                getValueOrDefault(event, field, e -> e.getValue(field.getName()))),
                getTypeOrNull(TypeIdent.of(field)),
                reduction);
    }

    private ValueDescriptor getField(ValueDescriptor parent, String fieldName) {
        return parent.getFields().stream()
                .filter(f -> f.getName().equals(fieldName))
                .findFirst()
                .orElseThrow();
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

    private FloatType percentageFloatType;

    private FloatType getPercentageFloatType() {
        if (percentageFloatType == null) {
            percentageFloatType =
                    out.writeAndStoreType(id -> new FloatType(id, "percentage", "", Type.BFLOAT16));
        }
        return percentageFloatType;
    }

    private VarIntType getMemoryVarIntType(String kind) {
        return memoryVarIntTypes.computeIfAbsent(
                kind,
                k -> {
                    // DataAmount(BYTES) is used by many counters (for example I/O bytesRead)
                    // that are not guaranteed to be 8-byte aligned.
                    long multiplier = 1;
                    return out.writeAndStoreType(
                            id -> new VarIntType(id, "memory varint " + k, "", true, multiplier));
                });
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
        // Use the no-arg getDuration() for the built-in event duration field,
        // because getDuration("duration") requires @Timespan annotation which
        // may be missing in some JFR file chunks.
        String fieldName = field.getName();
        boolean builtinDuration = fieldName.equals("duration") && topLevel;
        Function<RecordedObject, Object> getter =
                builtinDuration
                        ? event -> ((RecordedEvent) event).getDuration()
                        : event -> {
                            // JFR uses Long.MIN_VALUE as the "unset" sentinel for @Timespan longs
                            // (e.g. GCConfiguration.pauseTarget = N/A). Routing it through Duration
                            // and TimeUtil.clamp would collapse it to a bogus -365d value, so
                            // detect
                            // the raw sentinel and carry it as Duration.ofNanos(Long.MIN_VALUE),
                            // which JFRReduction.TIMESPAN_REDUCTION preserves losslessly.
                            long raw = event.getLong(fieldName);
                            if (raw == Long.MIN_VALUE) {
                                return Duration.ofNanos(Long.MIN_VALUE);
                            }
                            // JFR uses Long.MAX_VALUE as the "Forever" sentinel for @Timespan
                            // longs (e.g. ActiveRecording.maxAge/recordingDuration). getDuration
                            // returns Duration.ofMillis(Long.MAX_VALUE), which TimeUtil.clamp
                            // collapses to a bogus 365d. Carry it as
                            // Duration.ofNanos(Long.MAX_VALUE)
                            // so JFRReduction.TIMESPAN_REDUCTION can preserve it losslessly.
                            if (raw == Long.MAX_VALUE) {
                                return Duration.ofNanos(Long.MAX_VALUE);
                            }
                            return event.getDuration(fieldName);
                        };
        return new GetterAndCachedType(
                getter,
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
        var arr = new ArrayList<>();
        arr.add(type.getLabel());
        arr.add(type.getDescription());
        return PrettyPrinter.compactPrint(arr);
    }

    public record ParsedEventDescription(String label, String description) {}

    public static ParsedEventDescription parseEventDescription(String description) {
        List<Object> arr;
        try {
            arr = Util.asList(JSONParser.parse(description));
        } catch (IOException e) {
            throw new IllegalStateException("Invalid description: " + description, e);
        }
        return new ParsedEventDescription((String) arr.get(0), (String) arr.get(1));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    StructType<RecordedEvent, Map<String, Object>> createAndRegisterEventStructType(
            EventType eventType) {
        return out.writeAndStoreType(
                id -> {
                    var removedFields =
                            ReducedJFRTypes.getRemovedFields(
                                    eventType.getName(), configuration, false);
                    var fields =
                            eventType.getFields().stream()
                                    .filter(e -> !removedFields.contains(e.getName()))
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
        var t =
                StructReflectionUtil.createStructWithPrimitiveFields(
                        out.getTypeCollection(), Configuration.class);
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

    /**
     * Records the source recording's {@code gmtOffset} (milliseconds east of UTC) so it is
     * persisted with the universe and can be re-injected at inflate. Must be called before {@link
     * #writeConfigurationAndUniverseIfNeeded}. {@link Universe#GMT_OFFSET_UNSET} leaves it unset.
     */
    public void setGmtOffsetMillis(long gmtOffsetMillis) {
        universe.setGmtOffsetMillis(gmtOffsetMillis);
    }

    public void processEvent(RecordedEvent event) {
        if (ignoreEvent(event)) {
            return;
        }
        footerCollector.collect(event);
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

    /** Reset deduplication state, call between processing different JFR files */
    public void resetDeduplication() {
        deduplication.reset();
    }

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

    /**
     * Reads the recording start time (nanos since epoch) from the JFR chunk header. The chunk
     * header stores the actual recording start at byte offset 32.
     */
    public static long readChunkStartTimeNanos(Path jfrFile) throws IOException {
        try (var raf = new java.io.RandomAccessFile(jfrFile.toFile(), "r")) {
            raf.seek(32); // skip magic(4) + version(4) + chunkSize(8) + cpOffset(8) + metaOffset(8)
            return raf.readLong();
        }
    }

    /**
     * Reads the source recording's {@code gmtOffset} (milliseconds east of UTC) from the JFR chunk
     * metadata region, so it can be preserved across a condense→inflate roundtrip (otherwise the
     * inflated file loses its timezone and {@code jfr print} renders every timestamp in UTC).
     *
     * <p>The JFR metadata event lives at chunk-header offset 0x18 (byte 24). Its layout mirrors
     * what the JMC writer produces: a LEB128 event size + type id, then the header longs
     * (startTime, duration, metadataId), then a LEB128 string-constant pool, then the element tree.
     * The root element's children include a {@code <region>} element whose {@code gmtOffset}
     * attribute carries the value as a decimal string (e.g. {@code "3600000"} for CET). Returns
     * {@link Universe#GMT_OFFSET_UNSET} if the file has no region gmtOffset or cannot be parsed.
     */
    public static long readChunkGmtOffsetMillis(Path jfrFile) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(jfrFile);
            if (data.length < 32
                    || data[0] != 'F'
                    || data[1] != 'L'
                    || data[2] != 'R'
                    || data[3] != 0) {
                return Universe.GMT_OFFSET_UNSET;
            }
            long metaOff = readLongRaw(data, 24); // chunk-header metadataOffset
            if (metaOff <= 0 || metaOff >= data.length) {
                return Universe.GMT_OFFSET_UNSET;
            }
            long[] cursor = {metaOff};
            readVarLong(data, cursor); // event size
            readVarLong(data, cursor); // event type id (0 = metadata)
            readVarLong(data, cursor); // startTime
            readVarLong(data, cursor); // duration
            readVarLong(data, cursor); // metadataId
            long stringCount = readVarLong(data, cursor);
            if (stringCount < 0 || stringCount > data.length) {
                return Universe.GMT_OFFSET_UNSET;
            }
            String[] strings = new String[(int) stringCount];
            for (int i = 0; i < stringCount; i++) {
                strings[i] = readMetadataString(data, cursor);
            }
            String gmtOffset = findRegionGmtOffset(data, cursor, strings);
            if (gmtOffset == null) {
                return Universe.GMT_OFFSET_UNSET;
            }
            return Long.parseLong(gmtOffset.trim());
        } catch (RuntimeException | java.io.IOException e) {
            return Universe.GMT_OFFSET_UNSET;
        }
    }

    private static long readLongRaw(byte[] data, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[offset + i] & 0xffL);
        }
        return v;
    }

    /** Reads one LEB128 varint (7 bits/byte, little-endian, high bit = continue). */
    private static long readVarLong(byte[] data, long[] cursor) {
        long result = 0;
        int shift = 0;
        int pos = (int) cursor[0];
        while (true) {
            int b = data[pos++] & 0xff;
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        cursor[0] = pos;
        return result;
    }

    /**
     * Reads a single metadata-pool string. JMC encodes an encoding byte then a payload: 0=null,
     * 1=empty, 3=UTF-8 (varint length + bytes), 4=char array (varint length + varint chars),
     * 5=Latin-1 (varint length + bytes).
     */
    private static String readMetadataString(byte[] data, long[] cursor)
            throws java.io.IOException {
        int enc = data[(int) cursor[0]++] & 0xff;
        switch (enc) {
            case 0:
                return null;
            case 1:
                return "";
            case 3:
                {
                    int len = (int) readVarLong(data, cursor);
                    int pos = (int) cursor[0];
                    String s = new String(data, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                    cursor[0] = pos + len;
                    return s;
                }
            case 4:
                {
                    int len = (int) readVarLong(data, cursor);
                    StringBuilder sb = new StringBuilder(len);
                    for (int i = 0; i < len; i++) {
                        sb.append((char) readVarLong(data, cursor));
                    }
                    return sb.toString();
                }
            case 5:
                {
                    int len = (int) readVarLong(data, cursor);
                    int pos = (int) cursor[0];
                    String s =
                            new String(
                                    data, pos, len, java.nio.charset.StandardCharsets.ISO_8859_1);
                    cursor[0] = pos + len;
                    return s;
                }
            default:
                throw new java.io.IOException("Unknown metadata string encoding: " + enc);
        }
    }

    /**
     * Walks the metadata element tree (each element: nameIdx, attrCount, (keyIdx,valIdx)*,
     * childCount, children*) and returns the {@code gmtOffset} attribute value of the first element
     * that carries it (the {@code <region>} element), or null if none.
     */
    private static String findRegionGmtOffset(byte[] data, long[] cursor, String[] strings) {
        long nameIdx = readVarLong(data, cursor);
        long attrCount = readVarLong(data, cursor);
        String gmtOffset = null;
        for (long i = 0; i < attrCount; i++) {
            long keyIdx = readVarLong(data, cursor);
            long valIdx = readVarLong(data, cursor);
            if (keyIdx >= 0
                    && keyIdx < strings.length
                    && "gmtOffset".equals(strings[(int) keyIdx])) {
                if (valIdx >= 0 && valIdx < strings.length) {
                    gmtOffset = strings[(int) valIdx];
                }
            }
        }
        long childCount = readVarLong(data, cursor);
        for (long i = 0; i < childCount; i++) {
            String childGmt = findRegionGmtOffset(data, cursor, strings);
            if (childGmt != null && gmtOffset == null) {
                gmtOffset = childGmt;
            }
        }
        return gmtOffset;
    }

    /** Be sure to close the output stream after writing all events */
    public void processJFRFile(Path file) {
        try {
            universe.setGmtOffsetMillis(readChunkGmtOffsetMillis(file));
            writeConfigurationAndUniverseIfNeeded(readChunkStartTimeNanos(file));
        } catch (IOException e) {
            // fall back to first-event start time
        }
        try (var r = new RecordingFile(file)) {
            processJFRFile(r);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        writeConfigurationAndUniverseIfNeeded(defaultStartTimeNanos); // ensure universe is written
        eventCombiner.close();
        var footer =
                footerCollector.build(
                        universe.getStartTimeNanos() / 1000,
                        universe.getDuration().toNanos() / 1000);
        out.writeFooter(footer); // closes the compression wrapper, then writes the footer
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long estimateSize() {
        return out.estimateSize();
    }

    /** Predicted on-disk (compressed) size without forcing a flush. */
    public long estimateOnDiskSize() {
        return out.estimateOnDiskSize();
    }

    /** Flush buffered bytes through the compressor so {@link #estimateSize()} is accurate. */
    public void flush() {
        out.flush();
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
