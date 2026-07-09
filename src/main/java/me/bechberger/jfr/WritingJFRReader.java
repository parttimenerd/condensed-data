package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;
import static me.bechberger.jfr.TypeUtil.getTypedPrimitiveValue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.JFRReader;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.ArrayType;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StringType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.JFREventTypedValueCombiner.JFREventTypedValuedReconstitutor;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmc.flightrecorder.writer.RecordingImpl;
import org.openjdk.jmc.flightrecorder.writer.TypeImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedFieldImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedFieldValueImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedValueImpl;
import org.openjdk.jmc.flightrecorder.writer.api.*;
import org.openjdk.jmc.flightrecorder.writer.api.Types.Builtin;
import org.openjdk.jmc.flightrecorder.writer.api.Types.JDK;
import org.openjdk.jmc.flightrecorder.writer.api.Types.Predefined;

@JMCDependent
public class WritingJFRReader {

    /**
     * Wraps an OutputStream to prevent {@code close()} from propagating. Used so that {@link
     * RecordingImpl#close()} flushes its data without closing the underlying output stream,
     * allowing subsequent recordings to write to the same stream.
     */
    private static class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            flush(); // flush but don't close the underlying stream
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }
    }

    private final JFRReader reader;
    private final OutputStream outputStream;
    private RecordingImpl recording;
    private final Map<CondensedType<?, ?>, Predefined> typeMap = new IdentityHashMap<>();
    private final Map<CondensedType<?, ?>, Type> realTypeMap = new IdentityHashMap<>();
    private final JFREventTypedValuedReconstitutor reconstitutor =
            new JFREventTypedValuedReconstitutor(this);
    private final Queue<TypedValue> eventsToEmit = new ArrayDeque<>();
    private final Map<String, Integer> combinedEventCount = new HashMap<>();
    private final FastChunkWriter fastChunkWriter = new FastChunkWriter();

    /**
     * Cache for sub-struct TypedValue conversions. The condensed format's universe cache returns
     * the same ReadStruct instance for identical cached values (threads, stack traces, etc.), so
     * identity-based lookup avoids redundant conversions and allows the JMC writer to reuse
     * constant pool entries.
     */
    private final IdentityHashMap<ReadStruct, TypedValue> subStructCache = new IdentityHashMap<>();

    private int eventCount = 0;

    /** Rotate JMC recording chunks every N events to bound per-chunk memory */
    private static final int CHUNK_ROTATION_INTERVAL = 10_000_000;

    /** Add default values for removed fields that are not handled by the Java JFR API */
    @SuppressWarnings("unused") // stored for future use
    private final boolean shouldAddDefaultValuesIfNecessary;

    @SuppressWarnings("unused") // stored for future use
    private boolean addAllRemovedFields = false;

    public WritingJFRReader(
            JFRReader reader,
            OutputStream outputStream,
            boolean shouldAddDefaultValuesIfNecessary) {
        this.reader = reader;
        this.outputStream = outputStream;
        this.shouldAddDefaultValuesIfNecessary = shouldAddDefaultValuesIfNecessary;
    }

    public WritingJFRReader(JFRReader reader, OutputStream outputStream) {
        this(reader, outputStream, false);
    }

    public WritingJFRReader(BasicJFRReader reader) {
        this(reader, new ByteArrayOutputStream());
    }

    public void setAddAllRemovedFields(boolean addAllRemovedFields) {
        this.addAllRemovedFields = addAllRemovedFields;
    }

    public JFREventTypedValuedReconstitutor getReconstitutor() {
        return reconstitutor;
    }

    void initRecording() {
        initRecording(null);
    }

    /**
     * Initialises the output recording, deriving the chunk-header start time from the CJFR
     * universe.
     *
     * <p>The universe start time can still be unresolved ({@code -1}) when the very first event is
     * read, which previously made the code silently fall back to a start of 1 ns — producing a JFR
     * header dated 1970-01-01 (observed in a real condense→inflate run). When a {@code
     * firstEventStart} is available we use it as the fallback instead of the meaningless 1 ns, so
     * the header reflects reality rather than the epoch.
     */
    void initRecording(@Nullable Instant firstEventStart) {
        long startTimeNanos =
                reader.getStartTime().getEpochSecond() * 1_000_000_000L
                        + reader.getStartTime().getNano();
        if (startTimeNanos <= 0 && firstEventStart != null) {
            long fromEvent =
                    firstEventStart.getEpochSecond() * 1_000_000_000L + firstEventStart.getNano();
            if (fromEvent > 0) {
                startTimeNanos = fromEvent;
            }
        }
        long durationNanos = Math.max(1, reader.getDuration().toNanos());
        // Use epoch-based startTicks so JFR reader computes:
        //   wall_time = startNanos + (eventTick - startTicks) = eventTick
        // For uninitialized universe (no events read yet), fall back to safe defaults
        if (startTimeNanos <= 0) {
            System.err.println(
                    "WARNING: recording start time could not be resolved; the inflated JFR chunk"
                        + " header will be dated near 1970-01-01. This indicates the CJFR universe"
                        + " start time and the first event's startTime were both unavailable.");
        }
        long startTicks = startTimeNanos > 0 ? startTimeNanos : 1;
        long startTimestamp = startTimeNanos > 0 ? startTimeNanos : 1;
        this.recording =
                (RecordingImpl)
                        Recordings.newRecording(
                                new NonClosingOutputStream(outputStream),
                                r -> {
                                    r.withStartTicks(startTicks);
                                    r.withTimestamp(startTimestamp);
                                    r.withDuration(durationNanos);
                                });
    }

    public @Nullable TypedValue readNextJFREvent() {
        if (!eventsToEmit.isEmpty()) {
            var event = eventsToEmit.poll();
            fastWriteEvent(event);
            maybeRotateChunk();
            return event;
        }
        var event = reader.readNextEvent();
        if (event == null) {
            return null;
        }
        if (recording == null) {
            Instant firstEventStart = null;
            try {
                firstEventStart = event.getInstant("startTime");
            } catch (RuntimeException ignored) {
                // event without a resolvable startTime; fall back to universe-derived value
            }
            initRecording(firstEventStart);
        }
        if (reconstitutor.isCombinedEvent(event)) {
            combinedEventCount.put(
                    event.getType().getName(),
                    combinedEventCount.getOrDefault(event.getType().getName(), 0) + 1);
            eventsToEmit.addAll(reconstitutor.reconstitute(this.reader.getInputStream(), event));
            return readNextJFREvent(); // comes in handy when the combined events
        }
        var evt = toTypedValue(event, true, ROOT_PATH);
        fastWriteEvent(evt);
        maybeRotateChunk();
        return evt;
    }

    private void fastWriteEvent(TypedValue event) {
        fastChunkWriter.writeEvent(recording, (TypedValueImpl) event);
    }

    private void maybeRotateChunk() {
        if (++eventCount % CHUNK_ROTATION_INTERVAL == 0) {
            // Rotate the JMC recording's internal chunk buffer to bound memory.
            // This flushes the current chunk's serialized event data to the output queue
            // while keeping the same Recording instance with all its types and constant pools.
            // The sub-struct cache is cleared to further reduce memory, but type maps are
            // preserved since the Recording's types remain valid.
            recording.rotateChunk();
            subStructCache.clear();
        }
    }

    public Map<String, Integer> getCombinedEventCount() {
        return Collections.unmodifiableMap(combinedEventCount);
    }

    public CondensedType<?, ?> getCondensedType(String name) {
        return Objects.requireNonNull(
                reader.getInputStream().getTypeCollection().getTypeOrNull(name));
    }

    /** Turn a read struct with a known event type into a typed value */
    public TypedValue fromReadStruct(ReadStruct struct) {
        return toTypedValue(struct, true, ROOT_PATH);
    }

    public List<TypedValue> readAllJFREvents() {
        List<TypedValue> events = new ArrayList<>();
        TypedValue evt;
        while ((evt = readNextJFREvent()) != null) {
            events.add(evt);
        }
        return events;
    }

    private Predefined getPredefType(CondensedType<?, ?> type, boolean isEvent) {
        var existing = typeMap.get(type);
        if (existing != null) {
            return existing;
        }
        Predefined predef = type::getName;
        typeMap.put(type, predef);
        realTypeMap.put(type, createType(type, isEvent));
        return predef;
    }

    private Type getRealType(CondensedType<?, ?> type, boolean isEvent) {
        return Objects.requireNonNull(realTypeMap.get(type));
    }

    private @Nullable Predefined getPredefinedTypeOfBuiltin(String name) {
        return switch (name) {
            case "boolean" -> Builtin.BOOLEAN;
            case "byte" -> Builtin.BYTE;
            case "char" -> Builtin.CHAR;
            case "short" -> Builtin.SHORT;
            case "int" -> Builtin.INT;
            case "long" -> Builtin.LONG;
            case "float" -> Builtin.FLOAT;
            case "double" -> Builtin.DOUBLE;
            case "String", "java.lang.String" -> Builtin.STRING;
            default -> null;
        };
    }

    private @Nullable Predefined getPredefinedJDKAnnotation(String name) {
        var predef = getPredefinedJDKType(name);
        if (predef != null && predef.toString().startsWith("ANNOTATION")) {
            return predef;
        }
        return null;
    }

    private @Nullable Predefined getPredefinedJDKType(String name) {
        return JDK_TYPE_CACHE.get(name);
    }

    private final Map<String, Type> customAnnotationTypes = new HashMap<>();

    /** Cache parsed field descriptions to avoid re-parsing JSON per event */
    private final Map<String, BasicJFRWriter.ParsedFieldDescription> parsedFieldDescriptionCache =
            new HashMap<>();

    /** Cache JDK type lookups to avoid linear scan of JDK.values() */
    private static final Map<String, Predefined> JDK_TYPE_CACHE;

    static {
        JDK_TYPE_CACHE = new HashMap<>();
        for (var jdk : JDK.values()) {
            JDK_TYPE_CACHE.put(jdk.getTypeName(), jdk);
        }
    }

    /** Pre-computed field processing info to avoid repeated lookups per event */
    private enum FieldCategory {
        NORMAL,
        TIMESTAMP,
        TIMESPAN
    }

    /** Pre-computed unit divisor for timestamp/timespan conversion (avoids stream per event) */
    private enum UnitDivisor {
        NANOS(1),
        MICROS(1_000),
        MILLIS(1_000_000),
        SECONDS(1_000_000_000);
        final long divisor;

        UnitDivisor(long divisor) {
            this.divisor = divisor;
        }
    }

    private record FieldPlanEntry(
            StructType.Field<?, ?, ?> condensedField,
            FieldCategory category,
            UnitDivisor unitDivisor,
            boolean isTimespanArray) {}

    private final IdentityHashMap<CondensedType<?, ?>, Map<String, FieldPlanEntry>> fieldPlanCache =
            new IdentityHashMap<>();

    /** Pre-computed JMC field info per type to bypass TypedValueBuilderImpl */
    private final IdentityHashMap<Type, List<TypedFieldImpl>> jmcFieldsCache =
            new IdentityHashMap<>();

    /** Cached null TypedFieldValueImpl per field (avoids re-allocation per event) */
    private final IdentityHashMap<TypedFieldImpl, TypedFieldValueImpl> nullFieldValueCache =
            new IdentityHashMap<>();

    private TypedFieldValueImpl getNullFieldValue(TypedFieldImpl field) {
        return nullFieldValueCache.computeIfAbsent(
                field, f -> new TypedFieldValueImpl(f, (TypedValueImpl) f.getType().nullValue()));
    }

    private List<TypedFieldImpl> getJmcFields(Type type) {
        return jmcFieldsCache.computeIfAbsent(type, t -> ((TypeImpl) t).getFields());
    }

    private static UnitDivisor computeTimespanUnit(BasicJFRWriter.ParsedFieldDescription parsed) {
        String unit = "NANOSECONDS";
        for (var a : parsed.annotations()) {
            if (a.type().equals("jdk.jfr.Timespan")) {
                unit = a.values().isEmpty() ? "NANOSECONDS" : (String) a.values().get(0);
                break;
            }
        }
        return switch (unit) {
            case "MICROSECONDS" -> UnitDivisor.MICROS;
            case "MILLISECONDS" -> UnitDivisor.MILLIS;
            case "SECONDS" -> UnitDivisor.SECONDS;
            default -> UnitDivisor.NANOS; // NANOSECONDS, TICKS
        };
    }

    private static UnitDivisor computeTimestampUnit(BasicJFRWriter.ParsedFieldDescription parsed) {
        for (var a : parsed.annotations()) {
            if (a.type().equals("jdk.jfr.Timestamp")) {
                String unit = a.values().isEmpty() ? "TICKS" : (String) a.values().get(0);
                if ("MILLISECONDS_SINCE_EPOCH".equals(unit)) {
                    return UnitDivisor.MILLIS;
                }
                break;
            }
        }
        return UnitDivisor.NANOS;
    }

    private FieldPlanEntry getFieldPlan(StructType<?, ?> structType, String fieldName) {
        Map<String, FieldPlanEntry> plan = fieldPlanCache.get(structType);
        if (plan == null) {
            plan = new HashMap<>();
            for (var f : structType.getFields()) {
                String typeName = f.getTypeName();
                FieldCategory cat;
                UnitDivisor unit = UnitDivisor.NANOS;
                boolean isTimespanArr = false;
                if (typeName.equals("timestamp")) {
                    cat = FieldCategory.TIMESTAMP;
                    unit = computeTimestampUnit(getCachedParsedDescription(f.description()));
                } else if (typeName.equals("timespan")) {
                    cat = FieldCategory.TIMESPAN;
                    unit = computeTimespanUnit(getCachedParsedDescription(f.description()));
                    isTimespanArr = f.type() instanceof ArrayType;
                } else {
                    cat = FieldCategory.NORMAL;
                }
                plan.put(f.name(), new FieldPlanEntry(f, cat, unit, isTimespanArr));
            }
            fieldPlanCache.put(structType, plan);
        }
        return plan.get(fieldName);
    }

    /**
     * Get or create a custom annotation type. We can't use the predefined JDK annotation types
     * because they are not initialized in the recording (to avoid conflicts with custom types from
     * CJFR).
     */
    private Type getOrCreateAnnotationType(String name, boolean hasValue) {
        return customAnnotationTypes.computeIfAbsent(
                name,
                n ->
                        recording.registerAnnotationType(
                                n,
                                builder -> {
                                    if (hasValue) {
                                        builder.addField("value", Builtin.STRING);
                                    }
                                }));
    }

    /**
     * Convert a Duration to the unit specified by the @Timespan annotation in the field
     * description. The condensed format always stores durations in nanoseconds, but the JFR file
     * may expect a different unit (MILLISECONDS, MICROSECONDS, SECONDS).
     */
    private BasicJFRWriter.ParsedFieldDescription getCachedParsedDescription(
            String fieldDescription) {
        return parsedFieldDescriptionCache.computeIfAbsent(
                fieldDescription, BasicJFRWriter::parseFieldDescription);
    }

    static long convertTimespanToUnit(Duration duration, String fieldDescription) {
        return convertTimespanToUnit(
                duration, BasicJFRWriter.parseFieldDescription(fieldDescription));
    }

    private static long convertTimespanToUnit(
            Duration duration, BasicJFRWriter.ParsedFieldDescription parsed) {
        return duration.toNanos() / computeTimespanUnit(parsed).divisor;
    }

    /** Fast path: pre-computed unit divisor, no stream/filter allocation */
    private static long convertTimespanToUnit(Duration duration, UnitDivisor unit) {
        return duration.toNanos() / unit.divisor;
    }

    /**
     * Convert an Instant to the unit specified by the @Timestamp annotation in the field
     * description. The condensed format always stores timestamps as epoch nanoseconds, but the JFR
     * file may expect epoch milliseconds for @Timestamp("MILLISECONDS_SINCE_EPOCH") fields.
     */
    static long convertTimestampToUnit(Instant instant, String fieldDescription) {
        return convertTimestampToUnit(
                instant, BasicJFRWriter.parseFieldDescription(fieldDescription));
    }

    private static long convertTimestampToUnit(
            Instant instant, BasicJFRWriter.ParsedFieldDescription parsed) {
        return toNanoSeconds(instant) / computeTimestampUnit(parsed).divisor;
    }

    /** Fast path: pre-computed unit divisor, no stream/filter allocation */
    private static long convertTimestampToUnit(Instant instant, UnitDivisor unit) {
        return toNanoSeconds(instant) / unit.divisor;
    }

    private boolean putJFRTypeIntoConstantPool(CondensedType<?, ?> type) {
        return (type instanceof StructType<?, ?> && !type.getName().contains("StackFrame"))
                || type instanceof StringType;
    }

    private Type createType(CondensedType<?, ?> type, boolean isEvent) {
        Consumer<TypeStructureBuilder> builderConsumer =
                builder -> {
                    if (type instanceof StructType<?, ?> structType) {
                        for (var field : structType.getFields()) {
                            var parsed = BasicJFRWriter.parseFieldDescription(field.description());
                            var isArray = parsed.isArray();
                            var innerType =
                                    isArray
                                            ? ((ArrayType<?, ?>) field.type()).getValueType()
                                            : field.type();
                            Consumer<TypedFieldBuilder> fieldBuilderConsumer =
                                    (TypedFieldBuilder b) -> {
                                        if (isArray) {
                                            b.asArray();
                                        }
                                        for (var ann : parsed.annotations()) {
                                            boolean hasValue = !ann.values().isEmpty();
                                            Type annotationType =
                                                    getOrCreateAnnotationType(ann.type(), hasValue);
                                            if (hasValue) {
                                                b.addAnnotation(
                                                        annotationType,
                                                        ann.values().get(0).toString());
                                            } else {
                                                b.addAnnotation(annotationType);
                                            }
                                        }
                                    };
                            var builtin = getPredefinedTypeOfBuiltin(parsed.type());
                            if (builtin != null) {
                                builder.addField(field.name(), builtin, fieldBuilderConsumer);
                            } else {
                                var jdk = getPredefinedJDKType(field.name());
                                if (jdk != null) {
                                    builder.addField(field.name(), jdk, fieldBuilderConsumer);
                                } else {
                                    var predef = getPredefType(innerType, false);
                                    if (realTypeMap.containsKey(innerType)) {
                                        builder.addField(
                                                field.name(),
                                                getRealType(innerType, false),
                                                fieldBuilderConsumer);
                                    } else {
                                        builder.addField(
                                                field.name(), predef, fieldBuilderConsumer);
                                    }
                                }
                            }
                        }
                        return;
                    }
                    throw new IllegalArgumentException("Unsupported type: " + type);
                };
        var result =
                isEvent
                        ? recording.registerType(type.getName(), "jdk.jfr.Event", builderConsumer)
                        : recording
                                .getTypes()
                                .getOrAdd(
                                        type.getName(),
                                        putJFRTypeIntoConstantPool(type),
                                        builderConsumer);
        return result;
    }

    // stackTrace, eventThread, startTime

    /** Linked list for cycle detection during struct-to-TypedValue conversion. */
    private record ReadStructPath(@Nullable ReadStructPath parent, @Nullable ReadStruct value) {

        ReadStructPath() {
            this(null, null);
        }

        ReadStructPath add(ReadStruct value) {
            return new ReadStructPath(this, value);
        }

        boolean contains(ReadStruct value, int minCount) {
            var current = this;
            while (current != null) {
                if (current.value == value) {
                    if (minCount == 0) {
                        return true;
                    }
                    minCount--;
                }
                current = current.parent;
            }
            return false;
        }
    }

    private static final ReadStructPath ROOT_PATH = new ReadStructPath();

    private static final TypedValueImpl[] EMPTY_TYPED_VALUE_ARRAY = new TypedValueImpl[0];

    private TypedValue toTypedValue(ReadStruct struct, boolean isEvent, ReadStructPath visited) {
        if (!isEvent) {
            TypedValue cached = subStructCache.get(struct);
            if (cached != null) {
                return cached;
            }
        }
        var structType = struct.getType();
        getPredefType(structType, isEvent);
        if (visited.contains(struct, 1)) {
            return getRealType(structType, isEvent).nullValue();
        }
        var curVisited = visited.add(struct);
        // Bypass TypedValueBuilderImpl (which does stream().collect() per call)
        // by constructing TypedValueImpl directly from a field value map.
        TypeImpl jmcType = (TypeImpl) getRealType(structType, isEvent);
        List<TypedFieldImpl> jmcFields = getJmcFields(jmcType);
        Map<String, TypedFieldValueImpl> fieldValues = new HashMap<>(jmcFields.size() * 4 / 3 + 1);
        for (TypedFieldImpl field : jmcFields) {
            String fieldName = field.getName();
            Object value = struct.get(fieldName);
            if (value == null) {
                fieldValues.put(fieldName, getNullFieldValue(field));
                continue;
            }
            FieldPlanEntry plan = getFieldPlan(structType, fieldName);
            if (value instanceof ReadList<?> readList) {
                if (!field.isArray()) {
                    throw new IllegalArgumentException(
                            "Expected non-array, got: "
                                    + readList.size()
                                    + " elements for field "
                                    + fieldName);
                }
                if (readList.isEmpty()) {
                    fieldValues.put(
                            fieldName, new TypedFieldValueImpl(field, EMPTY_TYPED_VALUE_ARRAY));
                } else {
                    TypedValueImpl[] arr = new TypedValueImpl[readList.size()];
                    boolean isTimespanArr = plan != null && plan.isTimespanArray();
                    UnitDivisor timespanUnit = isTimespanArr ? plan.unitDivisor() : null;
                    int i = 0;
                    for (var val : readList) {
                        Object element = val;
                        if (element instanceof Duration && isTimespanArr) {
                            element = convertTimespanToUnit((Duration) element, timespanUnit);
                        }
                        var prim = getTypedPrimitiveValue(field, element);
                        if (prim != null) {
                            arr[i++] = (TypedValueImpl) prim;
                        } else {
                            arr[i++] =
                                    (TypedValueImpl)
                                            toTypedValue((ReadStruct) element, false, curVisited);
                        }
                    }
                    fieldValues.put(fieldName, new TypedFieldValueImpl(field, arr));
                }
            } else {
                if (field.isArray()) {
                    throw new IllegalArgumentException("Expected array, got: " + value);
                }
                if (plan != null) {
                    FieldCategory cat = plan.category();
                    if (cat == FieldCategory.TIMESTAMP) {
                        // Fast path: avoid double autoboxing by calling asValue(long) directly
                        long nanos;
                        if (value instanceof Instant inst) {
                            nanos = convertTimestampToUnit(inst, plan.unitDivisor());
                        } else if (value instanceof Duration dur) {
                            nanos = dur.toNanos();
                        } else if (value instanceof Long l) {
                            nanos = l;
                        } else {
                            throw new IllegalArgumentException(
                                    "Expected Instant, Duration or Long (nanoseconds) for "
                                            + fieldName
                                            + ", got: "
                                            + value);
                        }
                        fieldValues.put(
                                fieldName,
                                new TypedFieldValueImpl(
                                        field, (TypedValueImpl) field.getType().asValue(nanos)));
                        continue;
                    } else if (cat == FieldCategory.TIMESPAN && value instanceof Duration dur) {
                        long converted = convertTimespanToUnit(dur, plan.unitDivisor());
                        fieldValues.put(
                                fieldName,
                                new TypedFieldValueImpl(
                                        field,
                                        (TypedValueImpl) field.getType().asValue(converted)));
                        continue;
                    }
                }
                var prim = getTypedPrimitiveValue(field, value);
                if (prim != null) {
                    fieldValues.put(
                            fieldName, new TypedFieldValueImpl(field, (TypedValueImpl) prim));
                } else {
                    fieldValues.put(
                            fieldName,
                            new TypedFieldValueImpl(
                                    field,
                                    (TypedValueImpl)
                                            toTypedValue((ReadStruct) value, false, curVisited)));
                }
            }
        }
        var result = (TypedValueImpl) jmcType.asValue((Object) fieldValues);
        if (!isEvent) {
            subStructCache.put(struct, result);
        }
        return result;
    }

    public void close() {
        try {
            if (recording == null) {
                initRecording();
            }
            recording.close();
            outputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The correct recording duration in nanoseconds, known only after all events have been read
     * (the CJFR's {@code lastStartTimeNanos} is advanced as timestamps are inflated). Always at
     * least 1 ns so the JFR chunk header never carries a zero/negative duration.
     */
    public long getActualDurationNanos() {
        return Math.max(1, reader.getDuration().toNanos());
    }

    /** JFR chunk header layout: magic(4) 'FLR\0', major(2), minor(2), chunkSize(8) @ offset 8. */
    private static final byte[] JFR_MAGIC = {'F', 'L', 'R', 0};

    /** Offset of the duration field within a JFR chunk header (after startNanos @ 32). */
    private static final int CHUNK_HEADER_DURATION_OFFSET = 40;

    /** Offset of the chunk size field within a JFR chunk header. */
    private static final int CHUNK_HEADER_SIZE_OFFSET = 8;

    /**
     * Upper bound on a plausible recording duration: 366 days in nanoseconds. Values above this
     * indicate the {@code lastStartTimeNanos}/{@code startTimeNanos} arithmetic went wrong (as in
     * the observed 62287129 s ≈ 721 day corruption), not a genuinely long recording.
     */
    private static final long MAX_PLAUSIBLE_DURATION_NANOS = 366L * 24 * 60 * 60 * 1_000_000_000L;

    /**
     * Writes {@code durationNanos} into the duration field (offset 40) of every chunk header in a
     * JFR file, without reflectively mutating JMC's final {@code RecordingImpl.duration}.
     *
     * <p>JMC's {@code RecordingImpl} sets its {@code duration} field at construction, but the true
     * recording span is only known after all events have been inflated. The previous approach
     * mutated that final field via reflection; on JDK 26 reflective final-field mutation is
     * deprecated (and will be blocked in a future release), and on failure it silently fell back to
     * a near-zero duration. Patching the serialized header afterward is annotation- and
     * reflection-free, so it cannot regress the same way.
     *
     * @throws IOException if the file cannot be read/written, or is not a valid JFR file
     * @throws IllegalArgumentException if {@code durationNanos} is non-positive or implausibly
     *     large
     */
    public static void patchChunkHeaderDuration(Path jfrFile, long durationNanos)
            throws IOException {
        if (durationNanos <= 0) {
            throw new IllegalArgumentException(
                    "Refusing to write non-positive chunk-header duration "
                            + durationNanos
                            + " ns into "
                            + jfrFile
                            + "; the recording duration was not resolved correctly");
        }
        if (durationNanos > MAX_PLAUSIBLE_DURATION_NANOS) {
            throw new IllegalArgumentException(
                    "Refusing to write implausibly large chunk-header duration "
                            + durationNanos
                            + " ns ("
                            + (durationNanos / 1_000_000_000L)
                            + " s) into "
                            + jfrFile
                            + "; this indicates corrupt start/last-timestamp bookkeeping");
        }
        try (var raf = new java.io.RandomAccessFile(jfrFile.toFile(), "rw")) {
            long fileLength = raf.length();
            long chunkStart = 0;
            byte[] magic = new byte[JFR_MAGIC.length];
            while (chunkStart + CHUNK_HEADER_DURATION_OFFSET + 8 <= fileLength) {
                raf.seek(chunkStart);
                raf.readFully(magic);
                if (!Arrays.equals(magic, JFR_MAGIC)) {
                    throw new IOException(
                            "Not a JFR chunk at offset "
                                    + chunkStart
                                    + " in "
                                    + jfrFile
                                    + " (bad magic)");
                }
                raf.seek(chunkStart + CHUNK_HEADER_SIZE_OFFSET);
                long chunkSize = raf.readLong();
                raf.seek(chunkStart + CHUNK_HEADER_DURATION_OFFSET);
                raf.writeLong(durationNanos);
                if (chunkSize <= 0) {
                    break; // size not finalized; only one chunk expected
                }
                chunkStart += chunkSize;
            }
        }
    }

    public static void toJFRFile(JFRReader reader, Path output) {
        Path path = toJFRFile(reader);
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(path, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path toJFRFile(JFRReader reader) {
        return toJFRFile(reader, false);
    }

    public static Path toJFRFile(JFRReader reader, boolean shouldAddDefaultValuesIfNecessary) {
        try {
            Path tmp = Files.createTempFile("recording", ".jfr");
            WritingJFRReader writingJFRReader =
                    new WritingJFRReader(
                            reader,
                            new BufferedOutputStream(Files.newOutputStream(tmp), 65536),
                            shouldAddDefaultValuesIfNecessary);
            long lastProgressTime = System.nanoTime();
            int count = 0;
            while (true) {
                var event = writingJFRReader.readNextJFREvent();
                if (event == null) {
                    break;
                }
                count++;
                long now = System.nanoTime();
                if (now - lastProgressTime >= 10_000_000_000L) { // every 10 seconds
                    System.err.printf("  inflate progress: %,d events written...%n", count);
                    lastProgressTime = now;
                }
            }
            writingJFRReader.close();
            // Patch the chunk-header duration reflection-free now that the true recording span is
            // known (all events read). JMC wrote a placeholder (>=1 ns) at construction time.
            patchChunkHeaderDuration(tmp, writingJFRReader.getActualDurationNanos());
            return tmp;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<RecordedEvent> toJFREventsList(BasicJFRReader reader) {
        return toJFREventsList(reader, Integer.MAX_VALUE, true);
    }

    public static List<RecordedEvent> toJFREventsList(
            BasicJFRReader reader, boolean shouldAddDefaultValuesIfNecessary) {
        return toJFREventsList(reader, Integer.MAX_VALUE, shouldAddDefaultValuesIfNecessary);
    }

    public static List<RecordedEvent> toJFREventsList(
            BasicJFRReader reader, int limit, boolean shouldAddDefaultValuesIfNecessary) {
        try {
            Path tmp = toJFRFile(reader, shouldAddDefaultValuesIfNecessary);
            List<RecordedEvent> events = new ArrayList<>();
            try (RecordingFile recordingFile = new RecordingFile(tmp)) {
                for (int i = 0; i < limit; i++) {
                    if (!recordingFile.hasMoreEvents()) {
                        break;
                    }
                    events.add(recordingFile.readEvent());
                }
            }
            Files.deleteIfExists(tmp);
            return events;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
