package me.bechberger.jfr;

import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Function;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.JFREventCombiner.*;
import me.bechberger.jfr.JFREventCombiner.MapEntry.*;

/**
 * Declarative specification for creating combiner + reconstitutor pairs. Eliminates the need to
 * write separate Combiner and Reconstitutor classes for each event type.
 *
 * <p>Two grouping strategies:
 *
 * <ul>
 *   <li>{@link #gcIdBased(String)} – groups by direct {@code gcId} field on the event
 *   <li>{@link #nextGcIdBased(String)} – groups by next GC cycle using timestamp lookup
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // GCReferenceStatistics: type → count (last-wins)
 * CombinerSpec.gcIdBased("jdk.GCReferenceStatistics")
 *     .mapKeyValue("type", "count", ValueDef.eventField())
 *
 * // TenuringDistribution: age → size (summed)
 * CombinerSpec.gcIdBased("jdk.TenuringDistribution")
 *     .mapKeyValue("age", "size", ValueDef.sumLong())
 *
 * // ThreadPark: parkedClass → count (event count)
 * CombinerSpec.nextGcIdBased("jdk.ThreadPark")
 *     .mapKeyValue("parkedClass", "count", ValueDef.countEvents())
 *     .reconstitute((builder, key, value) -> { ... })
 * }</pre>
 */
public class CombinerSpec {

    // ======================== Value Definitions ========================

    /** How to extract and aggregate a value from an event */
    public interface ValueDef {

        /** Use the event field's type from the JFR schema, last-wins on collision. */
        static FieldValue eventField() {
            return FieldValue.INSTANCE;
        }

        /** Sum long values across events with the same key */
        static SumLong sumLong() {
            return SumLong.INSTANCE;
        }

        /** Count the number of events with the same key */
        static CountEvents countEvents() {
            return CountEvents.INSTANCE;
        }

        /** Collect all values into an array */
        static CollectArray collectArray() {
            return CollectArray.INSTANCE;
        }

        /** Collect events as structs in an array, auto-detecting fields */
        static CollectStructArray collectStructArray(String structName, String... skipFields) {
            return new CollectStructArray(structName, Set.of(skipFields));
        }

        /** Store a struct with specified fields per key, last struct wins */
        static StructValue struct(String structName, String... fieldNames) {
            return new StructValue(structName, List.of(fieldNames));
        }

        /** Capture all non-standard fields as a struct dynamically */
        static DynamicStructValue dynamicStruct(String structName, String... skipFields) {
            return new DynamicStructValue(structName, Set.of(skipFields));
        }
    }

    static final class FieldValue implements ValueDef {
        static final FieldValue INSTANCE = new FieldValue();
    }

    static final class SumLong implements ValueDef {
        static final SumLong INSTANCE = new SumLong();
    }

    static final class CountEvents implements ValueDef {
        static final CountEvents INSTANCE = new CountEvents();
    }

    static final class CollectArray implements ValueDef {
        static final CollectArray INSTANCE = new CollectArray();
    }

    static final class CollectStructArray implements ValueDef {
        final String structName;
        final Set<String> skipFields;

        CollectStructArray(String structName, Set<String> skipFields) {
            this.structName = structName;
            this.skipFields = skipFields;
        }
    }

    static final class StructValue implements ValueDef {
        final String structName;
        final List<String> fieldNames;

        StructValue(String structName, List<String> fieldNames) {
            this.structName = structName;
            this.fieldNames = fieldNames;
        }
    }

    static final class DynamicStructValue implements ValueDef {
        final String structName;
        final Set<String> skipFields;

        DynamicStructValue(String structName, Set<String> skipFields) {
            this.structName = structName;
            this.skipFields = skipFields;
        }
    }

    // ======================== Custom Reconstitution ========================

    /** Custom reconstruction logic for a map entry */
    @FunctionalInterface
    public interface MapEntryReconstitutor {
        <E> List<E> reconstitute(EventBuilder<E, ?> builder, Object key, Object value);
    }

    /** Custom reconstruction logic for the entire combined event */
    @FunctionalInterface
    public interface FullReconstitutor {
        <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder);
    }

    // ======================== Grouping Strategy ========================

    public enum GroupingStrategy {
        GC_ID,
        NEXT_GC_ID
    }

    // ======================== Spec Fields ========================

    private final String originalEventTypeName;
    private final GroupingStrategy grouping;
    private String keyFieldName;
    private String valueFieldName;
    private ValueDef valueDef;
    private MapEntryReconstitutor mapEntryReconstitutor;
    private FullReconstitutor fullReconstitutor;

    @SuppressWarnings("rawtypes")
    private Function mapToListTransform;

    // Custom key type creator (for special types like VarInt or duration)
    @SuppressWarnings("rawtypes")
    private BiFunction customKeyTypeCreator;

    // Custom key value extractor (for special extractions like getLong)
    private Function<RecordedEvent, ?> customKeyExtractor;

    // Custom value type creator (for special types like duration)
    @SuppressWarnings("rawtypes")
    private BiFunction customValueTypeCreator;

    // Custom value extractor
    private Function<RecordedEvent, ?> customValueExtractor;

    private CombinerSpec(String originalEventTypeName, GroupingStrategy grouping) {
        this.originalEventTypeName = originalEventTypeName;
        this.grouping = grouping;
    }

    // ======================== Factory Methods ========================

    /** Create a spec that groups events by their direct {@code gcId} field. */
    public static CombinerSpec gcIdBased(String originalEventTypeName) {
        return new CombinerSpec(originalEventTypeName, GroupingStrategy.GC_ID);
    }

    /** Create a spec that groups events by next GC cycle via timestamp lookup. */
    public static CombinerSpec nextGcIdBased(String originalEventTypeName) {
        return new CombinerSpec(originalEventTypeName, GroupingStrategy.NEXT_GC_ID);
    }

    // ======================== Configuration Methods ========================

    /**
     * Define a key → value mapping.
     *
     * @param keyFieldName JFR field name to use as map key
     * @param valueFieldName field name for the value (or synthetic name for countEvents)
     * @param valueDef how to extract and aggregate the value
     */
    public CombinerSpec mapKeyValue(String keyFieldName, String valueFieldName, ValueDef valueDef) {
        this.keyFieldName = keyFieldName;
        this.valueFieldName = valueFieldName;
        this.valueDef = valueDef;
        return this;
    }

    /** Override key type creation (e.g., for VarInt, duration types). */
    @SuppressWarnings("rawtypes")
    public CombinerSpec keyType(BiFunction customKeyTypeCreator) {
        this.customKeyTypeCreator = customKeyTypeCreator;
        return this;
    }

    /** Override key value extraction. */
    public CombinerSpec keyExtractor(Function<RecordedEvent, ?> extractor) {
        this.customKeyExtractor = extractor;
        return this;
    }

    /** Override value type creation. */
    @SuppressWarnings("rawtypes")
    public CombinerSpec valueType(BiFunction customValueTypeCreator) {
        this.customValueTypeCreator = customValueTypeCreator;
        return this;
    }

    /** Override value extraction. */
    public CombinerSpec valueExtractor(Function<RecordedEvent, ?> extractor) {
        this.customValueExtractor = extractor;
        return this;
    }

    /** Custom map-to-list ordering/filtering for the output. */
    @SuppressWarnings("rawtypes")
    public CombinerSpec mapToList(Function mapToListTransform) {
        this.mapToListTransform = mapToListTransform;
        return this;
    }

    /** Override reconstruction with per-map-entry logic. */
    public CombinerSpec reconstitute(MapEntryReconstitutor reconstitutor) {
        this.mapEntryReconstitutor = reconstitutor;
        return this;
    }

    /** Override reconstruction with full custom logic. */
    public CombinerSpec reconstituteFull(FullReconstitutor reconstitutor) {
        this.fullReconstitutor = reconstitutor;
        return this;
    }

    // ======================== Getters ========================

    public String getOriginalEventTypeName() {
        return originalEventTypeName;
    }

    public String getCombinedEventTypeName() {
        return originalEventTypeName.replace("jdk.", "jdk.combined.");
    }

    public GroupingStrategy getGrouping() {
        return grouping;
    }

    // ======================== Build MapValue ========================

    @SuppressWarnings({"unchecked", "rawtypes"})
    MapValue<RecordedEvent, ?, ?> buildMapValue(BasicJFRWriter writer) {
        MapPartValue<RecordedEvent, ?> key = buildKeyPart(writer);
        MapEntry<RecordedEvent, ?> value = buildValueEntry(writer);

        Function<Map, List> toList =
                mapToListTransform != null
                        ? mapToListTransform
                        : map -> new ArrayList<>(map.entrySet());
        return new MapValue(key, value, toList);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MapPartValue<RecordedEvent, ?> buildKeyPart(BasicJFRWriter writer) {
        BiFunction typeCreator =
                customKeyTypeCreator != null
                        ? customKeyTypeCreator
                        : (BiFunction<CondensedOutputStream, EventType, CondensedType>)
                                (out, et) -> writer.getTypeCached(et.getField(keyFieldName));
        Function<RecordedEvent, ?> extractor =
                customKeyExtractor != null ? customKeyExtractor : e -> e.getValue(keyFieldName);
        return new MapPartValue(keyFieldName, typeCreator, extractor);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MapEntry<RecordedEvent, ?> buildValueEntry(BasicJFRWriter writer) {
        if (valueDef instanceof FieldValue) {
            return new SingleValue<>(buildValuePart(writer));
        } else if (valueDef instanceof SumLong) {
            return new SingleValue<>(
                    buildValuePart(writer), (BiFunction) (a, b) -> (long) a + (long) b, () -> 0L);
        } else if (valueDef instanceof CountEvents) {
            return new SingleValue<>(
                    new MapPartValue<RecordedEvent, Long>(
                            valueFieldName,
                            (out, eventType) ->
                                    (CondensedType<Long, Long>)
                                            (CondensedType)
                                                    TypeCollection.getDefaultTypeInstance(
                                                            VarIntType.SPECIFIED_TYPE),
                            e -> 1L),
                    Long::sum,
                    () -> 0L);
        } else if (valueDef instanceof CollectArray) {
            return new ArrayValue<>(buildValuePart(writer));
        } else if (valueDef instanceof CollectStructArray) {
            CollectStructArray csa = (CollectStructArray) valueDef;
            return new ArrayValue<>(
                    new MapPartValue<RecordedEvent, RecordedEvent>(
                            valueFieldName,
                            (out, eventType) ->
                                    (CondensedType)
                                            writer.getOutputStream()
                                                    .writeAndStoreType(
                                                            id ->
                                                                    buildDynamicStruct(
                                                                            writer,
                                                                            eventType,
                                                                            id,
                                                                            csa.structName,
                                                                            csa.skipFields)),
                            e -> e));
        } else if (valueDef instanceof StructValue) {
            StructValue sv = (StructValue) valueDef;
            return new SingleValue<>(
                    new MapPartValue<RecordedEvent, RecordedEvent>(
                            valueFieldName,
                            (out, eventType) ->
                                    (CondensedType)
                                            writer.getOutputStream()
                                                    .writeAndStoreType(
                                                            id ->
                                                                    buildNamedStruct(
                                                                            writer,
                                                                            eventType,
                                                                            id,
                                                                            sv.structName,
                                                                            sv.fieldNames)),
                            e -> e));
        } else if (valueDef instanceof DynamicStructValue) {
            DynamicStructValue dsv = (DynamicStructValue) valueDef;
            return new SingleValue<>(
                    new MapPartValue<RecordedEvent, RecordedEvent>(
                            valueFieldName,
                            (out, eventType) ->
                                    (CondensedType)
                                            writer.getOutputStream()
                                                    .writeAndStoreType(
                                                            id ->
                                                                    buildDynamicStruct(
                                                                            writer,
                                                                            eventType,
                                                                            id,
                                                                            dsv.structName,
                                                                            dsv.skipFields)),
                            e -> e));
        }
        throw new IllegalStateException("Unknown ValueDef: " + valueDef);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MapPartValue buildValuePart(BasicJFRWriter writer) {
        BiFunction typeCreator =
                customValueTypeCreator != null
                        ? customValueTypeCreator
                        : (BiFunction<CondensedOutputStream, EventType, CondensedType>)
                                (out, et) -> writer.getTypeCached(et.getField(valueFieldName));
        Function<RecordedEvent, ?> extractor =
                customValueExtractor != null
                        ? customValueExtractor
                        : e -> e.getValue(valueFieldName);
        return new MapPartValue(valueFieldName, typeCreator, extractor);
    }

    // ======================== Struct Builders ========================

    private static final Set<String> ALWAYS_SKIP = Set.of("startTime", "gcId", "eventThread");

    private static StructType<RecordedEvent, ReadStruct> buildDynamicStruct(
            BasicJFRWriter writer,
            EventType eventType,
            int id,
            String structName,
            Set<String> extraSkipFields) {
        List<Field<RecordedEvent, ?, ?>> fields = new ArrayList<>();
        for (var field : eventType.getFields()) {
            String name = field.getName();
            if (ALWAYS_SKIP.contains(name) || extraSkipFields.contains(name)) {
                continue;
            }
            fields.add(writer.eventFieldToField(field, true));
        }
        return new StructType<>(id, structName, fields);
    }

    private static StructType<RecordedEvent, ReadStruct> buildNamedStruct(
            BasicJFRWriter writer,
            EventType eventType,
            int id,
            String structName,
            List<String> fieldNames) {
        List<Field<RecordedEvent, ?, ?>> fields = new ArrayList<>();
        for (var fieldName : fieldNames) {
            fields.add(writer.eventFieldToField(eventType.getField(fieldName), true));
        }
        return new StructType<>(id, structName, fields);
    }

    // ======================== Combiner Creation ========================

    AbstractCombiner<?, ?> createCombiner(
            Configuration configuration, BasicJFRWriter writer, GCIdPerTimestamp gcIdPerTimestamp) {
        var mapValue = buildMapValue(writer);
        if (grouping == GroupingStrategy.GC_ID) {
            return new SpecGCIdCombiner(
                    getCombinedEventTypeName(), configuration, writer, mapValue);
        } else {
            return new SpecNextGCIdCombiner(
                    getCombinedEventTypeName(), configuration, writer, mapValue, gcIdPerTimestamp);
        }
    }

    // ======================== Reconstitutor Creation ========================

    AbstractReconstitutor<?> createReconstitutor() {
        String eventTypeName = originalEventTypeName;
        if (fullReconstitutor != null) {
            return new SpecReconstitutor(eventTypeName, fullReconstitutor);
        }
        if (mapEntryReconstitutor != null) {
            return new SpecMapEntryReconstitutor(
                    eventTypeName, keyFieldName, mapEntryReconstitutor);
        }
        return new SpecDefaultReconstitutor(eventTypeName, keyFieldName, valueFieldName, valueDef);
    }

    // ======================== Inner Combiner Classes ========================

    static class SpecGCIdCombiner extends GCIdBasedCombiner {
        SpecGCIdCombiner(
                String typeName,
                Configuration configuration,
                BasicJFRWriter writer,
                MapValue<RecordedEvent, ?, ?> mapValue) {
            super(typeName, configuration, writer, mapValue);
        }
    }

    static class SpecNextGCIdCombiner
            extends AbstractCombiner<ObjectAllocationSampleToken, ObjectAllocationSampleState> {

        private final GCIdPerTimestamp gcIdPerTimestamp;

        SpecNextGCIdCombiner(
                String typeName,
                Configuration configuration,
                BasicJFRWriter writer,
                MapValue<RecordedEvent, ?, ?> mapValue,
                GCIdPerTimestamp gcIdPerTimestamp) {
            super(typeName, configuration, writer, mapValue);
            this.gcIdPerTimestamp = gcIdPerTimestamp;
        }

        @Override
        public void combine(
                ObjectAllocationSampleToken token,
                ObjectAllocationSampleState state,
                RecordedEvent event) {
            state.updateEndTimestamp(event.getStartTime());
            super.combine(token, state, event);
        }

        @Override
        public Instant getStartTimestamp(ObjectAllocationSampleState state) {
            return state.endTimestamp;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Field<ObjectAllocationSampleState, ?, ?>> getAdditionalFields(
                EventType eventType) {
            return List.of(
                    new Field<>(
                            "nextGCId",
                            "",
                            TypeCollection.getDefaultTypeInstance(VarIntType.SPECIFIED_TYPE),
                            s -> s.nextGCId),
                    new Field<>(
                            "endTime",
                            "",
                            (CondensedType<Instant, Instant>)
                                    basicJFRWriter.getTypeCached(eventType.getField("startTime")),
                            s ->
                                    JFRReduction.TIMESTAMP_REDUCTION.reduce(
                                            configuration,
                                            basicJFRWriter.universe,
                                            s.endTimestamp)));
        }

        @Override
        public ObjectAllocationSampleState createInitialState(
                ObjectAllocationSampleToken token, RecordedEvent event) {
            var state = new DefinedMap<>(getValueDefinition(), event);
            return new ObjectAllocationSampleState(token.nextGCId(), state, event.getStartTime());
        }

        @Override
        public ObjectAllocationSampleToken createToken(RecordedEvent event) {
            return new ObjectAllocationSampleToken(
                    gcIdPerTimestamp.getClosestGCId(event.getStartTime()));
        }
    }

    // ======================== Inner Reconstitutor Classes ========================

    static class SpecReconstitutor extends AbstractReconstitutor<AbstractCombiner<?, ?>> {
        private final FullReconstitutor reconstitutor;

        SpecReconstitutor(String eventTypeName, FullReconstitutor reconstitutor) {
            super(eventTypeName);
            this.reconstitutor = reconstitutor;
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            return reconstitutor.reconstitute(resultEventType, combinedReadEvent, builder);
        }
    }

    static class SpecMapEntryReconstitutor extends AbstractReconstitutor<AbstractCombiner<?, ?>> {
        private final String keyFieldName;
        private final MapEntryReconstitutor reconstitutor;

        SpecMapEntryReconstitutor(
                String eventTypeName, String keyFieldName, MapEntryReconstitutor reconstitutor) {
            super(eventTypeName);
            this.keyFieldName = keyFieldName;
            this.reconstitutor = reconstitutor;
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            addGroupingFields(builder, combinedReadEvent);
            return combinedReadEvent.asMapEntryList(keyFieldName).stream()
                    .flatMap(
                            e ->
                                    ((List<E>)
                                                    reconstitutor.reconstitute(
                                                            builder, e.getKey(), e.getValue()))
                                            .stream())
                    .toList();
        }
    }

    /** Default reconstitutor: handles common patterns based on the ValueDef type. */
    static class SpecDefaultReconstitutor extends AbstractReconstitutor<AbstractCombiner<?, ?>> {
        private final String keyFieldName;
        private final String valueFieldName;
        private final ValueDef valueDef;

        SpecDefaultReconstitutor(
                String eventTypeName,
                String keyFieldName,
                String valueFieldName,
                ValueDef valueDef) {
            super(eventTypeName);
            this.keyFieldName = keyFieldName;
            this.valueFieldName = valueFieldName;
            this.valueDef = valueDef;
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            addGroupingFields(builder, combinedReadEvent);
            return combinedReadEvent.asMapEntryList(keyFieldName).stream()
                    .flatMap(e -> reconstituteEntry(builder, e).stream())
                    .toList();
        }

        private <E> List<E> reconstituteEntry(EventBuilder<E, ?> builder, Entry<?, ?> entry) {
            if (valueDef instanceof FieldValue || valueDef instanceof SumLong) {
                return List.of(
                        builder.put(keyFieldName, entry.getKey())
                                .put(valueFieldName, entry.getValue())
                                .build());
            } else if (valueDef instanceof CountEvents) {
                long count = ((Number) entry.getValue()).longValue();
                List<E> events = new ArrayList<>();
                for (long i = 0; i < count; i++) {
                    events.add(
                            builder.put(keyFieldName, entry.getKey())
                                    .put(valueFieldName, entry.getValue())
                                    .build());
                }
                return events;
            } else if (valueDef instanceof CollectArray) {
                List<?> values = (List<?>) entry.getValue();
                return values.stream()
                        .map(
                                v ->
                                        builder.put(keyFieldName, entry.getKey())
                                                .put(valueFieldName, v)
                                                .build())
                        .toList();
            } else if (valueDef instanceof CollectStructArray) {
                List<?> structs = (List<?>) entry.getValue();
                return structs.stream()
                        .map(
                                v -> {
                                    builder.put(keyFieldName, entry.getKey());
                                    copyStructFields(builder, (ReadStruct) v);
                                    return builder.build();
                                })
                        .toList();
            } else if (valueDef instanceof StructValue) {
                StructValue sv = (StructValue) valueDef;
                builder.put(keyFieldName, entry.getKey());
                ReadStruct data = (ReadStruct) entry.getValue();
                for (var fieldName : sv.fieldNames) {
                    builder.put(fieldName, data.get(fieldName));
                }
                return List.of(builder.build());
            } else if (valueDef instanceof DynamicStructValue) {
                builder.put(keyFieldName, entry.getKey());
                copyStructFields(builder, (ReadStruct) entry.getValue());
                return List.of(builder.build());
            }
            throw new IllegalStateException("Unknown ValueDef: " + valueDef);
        }
    }

    // ======================== Helpers ========================

    /** Add grouping-specific fields to the builder. */
    static <E> void addGroupingFields(EventBuilder<E, ?> builder, ReadStruct combinedReadEvent) {
        if (combinedReadEvent.hasField("gcId")) {
            builder.put("gcId");
        }
        if (combinedReadEvent.hasField("endTime")) {
            builder.put("endTime");
        }
        builder.addStandardFieldsIfNeeded();
    }

    /** Copy all fields from a ReadStruct into the builder. */
    static <E> void copyStructFields(EventBuilder<E, ?> builder, ReadStruct struct) {
        for (var field : struct.getType().getFields()) {
            builder.put(field.name(), struct.get(field.name()));
        }
    }

    // ======================== Predefined Specs ========================

    /**
     * Spec definitions for all combiners that can be expressed declaratively.
     *
     * <p>Each spec replaces a hand-written Combiner + Reconstitutor pair with a concise
     * declaration.
     */
    static final class Specs {

        private Specs() {}

        // --- combineEventsWithoutDataLoss ---

        static CombinerSpec gcReferenceStatistics() {
            return CombinerSpec.gcIdBased("jdk.GCReferenceStatistics")
                    .mapKeyValue("type", "count", ValueDef.eventField());
        }

        static CombinerSpec tenuringDistribution(Configuration config) {
            var spec =
                    CombinerSpec.gcIdBased("jdk.TenuringDistribution")
                            .mapKeyValue("age", "size", ValueDef.sumLong())
                            .keyType(
                                    (BiFunction<
                                                    CondensedOutputStream,
                                                    EventType,
                                                    CondensedType<?, ?>>)
                                            (out, et) -> out.writeAndStoreType(VarIntType::new))
                            .keyExtractor(e -> e.getLong("age"));
            if (config.ignoreZeroSizedTenuredAges()) {
                spec.mapToList(
                        (Function<Map<?, ?>, List<?>>)
                                map ->
                                        map.entrySet().stream()
                                                .filter(
                                                        e ->
                                                                ((Long)
                                                                                ((Entry<?, ?>) e)
                                                                                        .getValue())
                                                                        != 0L)
                                                .toList());
            }
            return spec;
        }

        @SuppressWarnings("rawtypes")
        static CombinerSpec gcPhasePauseLevel(
                String eventTypeName, Configuration config, BasicJFRWriter writer) {
            var spec =
                    CombinerSpec.gcIdBased(eventTypeName)
                            .mapKeyValue("name", "duration", ValueDef.eventField())
                            .valueType((BiFunction) (out, et) -> writer.getDurationType())
                            .valueExtractor(
                                    e ->
                                            me.bechberger.util.TimeUtil.clamp(e.getDuration())
                                                    .toNanos());
            if (config.ignoreTooShortGCPauses()) {
                spec.mapToList(
                        (Function<Map<?, ?>, List<?>>)
                                map ->
                                        map.entrySet().stream()
                                                .filter(
                                                        e ->
                                                                !writer.isEffectivelyZeroDuration(
                                                                        (Long)
                                                                                ((Entry<?, ?>) e)
                                                                                        .getValue()))
                                                .toList());
            }
            return spec;
        }

        static CombinerSpec objectCount(String eventTypeName) {
            return CombinerSpec.gcIdBased(eventTypeName)
                    .mapKeyValue(
                            "objectClass",
                            "countAndSize",
                            ValueDef.struct("ObjectCountData", "count", "totalSize"));
        }

        static CombinerSpec gcBeforeAfterSummary(String eventTypeName, String structName) {
            return CombinerSpec.gcIdBased(eventTypeName)
                    .mapKeyValue("when", "summaryData", ValueDef.dynamicStruct(structName, "when"));
        }

        // --- combineExceptionEvents ---

        static CombinerSpec javaExceptionThrow(String eventTypeName) {
            return CombinerSpec.nextGcIdBased(eventTypeName)
                    .mapKeyValue("thrownClass", "count", ValueDef.countEvents())
                    .reconstitute(
                            new MapEntryReconstitutor() {
                                @Override
                                public <E> List<E> reconstitute(
                                        EventBuilder<E, ?> builder, Object key, Object value) {
                                    long count = ((Number) value).longValue();
                                    List<E> events = new ArrayList<>(Math.toIntExact(count));
                                    for (long i = 0; i < count; i++) {
                                        events.add(
                                                builder.put("thrownClass", key)
                                                        .put("message", null)
                                                        .build());
                                    }
                                    return events;
                                }
                            });
        }

        // --- combineG1HeapRegionTypeChangeEvents ---

        static CombinerSpec g1HeapRegionTypeChange() {
            return CombinerSpec.nextGcIdBased("jdk.G1HeapRegionTypeChange")
                    .mapKeyValue(
                            "index",
                            "regionChange",
                            ValueDef.collectStructArray("G1RegionChange", "index", "start"))
                    .keyExtractor(e -> e.getLong("index"));
        }

        // --- combineBlockingEvents ---

        static CombinerSpec threadPark() {
            return CombinerSpec.nextGcIdBased("jdk.ThreadPark")
                    .mapKeyValue("parkedClass", "count", ValueDef.countEvents())
                    .reconstitute(
                            new MapEntryReconstitutor() {
                                @Override
                                public <E> List<E> reconstitute(
                                        EventBuilder<E, ?> builder, Object key, Object value) {
                                    long count = ((Number) value).longValue();
                                    List<E> events = new ArrayList<>(Math.toIntExact(count));
                                    for (long i = 0; i < count; i++) {
                                        events.add(
                                                builder.put("parkedClass", key)
                                                        .put("duration", 0L)
                                                        .put("timeout", 0L)
                                                        .put("until", 0L)
                                                        .put("address", 0L)
                                                        .build());
                                    }
                                    return events;
                                }
                            });
        }

        static CombinerSpec threadSleep() {
            return CombinerSpec.nextGcIdBased("jdk.ThreadSleep")
                    .mapKeyValue("time", "count", ValueDef.countEvents())
                    .reconstitute(
                            new MapEntryReconstitutor() {
                                @Override
                                public <E> List<E> reconstitute(
                                        EventBuilder<E, ?> builder, Object key, Object value) {
                                    long count = ((Number) value).longValue();
                                    List<E> events = new ArrayList<>(Math.toIntExact(count));
                                    for (long i = 0; i < count; i++) {
                                        events.add(
                                                builder.put("time", key)
                                                        .put("duration", 0L)
                                                        .build());
                                    }
                                    return events;
                                }
                            });
        }
    }
}
