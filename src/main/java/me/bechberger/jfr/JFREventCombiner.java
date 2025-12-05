package me.bechberger.jfr;

import static me.bechberger.condensed.types.TypeCollection.normalize;
import static me.bechberger.util.TimeUtil.clamp;

import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.ArrayType.WrappedArrayType;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.EventReconstitutor.Reconstitutor;
import me.bechberger.jfr.JFREventCombiner.MapEntry.ArrayValue;
import me.bechberger.jfr.JFREventCombiner.MapEntry.MapValue;
import me.bechberger.jfr.JFREventCombiner.MapEntry.SingleValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;

/** Contains combiners for JFR events to reduce the amount of data written to the stream */
public class JFREventCombiner extends EventCombiner {

    private static final Logger LOGGER = Logger.getLogger(JFREventCombiner.class.getName());

    /**
     * A typed field value
     *
     * @param <O> event that this field is part of
     * @param <R> type of the value
     */
    static class MapPartValue<O, R> {
        private final String name;
        private final @Nullable BiFunction<CondensedOutputStream, EventType, CondensedType<R, R>>
                typeCreator;
        private final Function<O, R> getter;

        public MapPartValue(
                String name,
                @Nullable
                        BiFunction<CondensedOutputStream, EventType, CondensedType<R, R>>
                                typeCreator,
                Function<O, R> getter) {
            this.name = name;
            this.typeCreator = typeCreator;
            this.getter = getter;
        }

        public CondensedType<R, ?> createType(CondensedOutputStream out, EventType eventType) {
            if (typeCreator == null) {
                throw new IllegalStateException("No type creator for " + name);
            }
            return typeCreator.apply(out, eventType);
        }

        @Override
        public String toString() {
            return name;
        }

        public R getValue(O object) {
            return getter.apply(object);
        }
    }

    /**
     * Node in the map hierarchy
     *
     * @param <O> event object type
     * @param <V> type of the value of the current node
     */
    public sealed interface MapEntry<O, V> {

        CondensedType<V, ?> createType(CondensedOutputStream out, EventType eventType);

        V defaultValue();

        /** Single value as an entry of a map */
        record SingleValue<O, V>(
                JFREventCombiner.MapPartValue<O, V> val,
                BiFunction<V, V, V> combiner,
                Supplier<V> defaultValueSupplier)
                implements MapEntry<O, V> {

            SingleValue(JFREventCombiner.MapPartValue<O, V> val) {
                this(val, (a, b) -> b, () -> null);
            }

            @Override
            public V defaultValue() {
                return defaultValueSupplier.get();
            }

            @Override
            public <V2 extends V> V insert(V2 valueInMap, O object) {
                return combiner.apply(valueInMap, val.getValue(object));
            }

            @Override
            public CondensedType<V, ?> createType(CondensedOutputStream out, EventType eventType) {
                return val.createType(out, eventType);
            }

            @Override
            public String toString() {
                return "{" + "val=" + val + '}';
            }
        }

        /** Array of values as an entry of a map */
        record ArrayValue<O, V>(MapPartValue<O, V> val, BiFunction<List<V>, V, List<V>> combiner)
                implements MapEntry<O, List<V>> {

            /**
             * Create an array value with a combiner that adds the value to the list
             *
             * @param val value definition
             */
            ArrayValue(MapPartValue<O, V> val) {
                this(
                        val,
                        (a, b) -> {
                            a.add(b);
                            return a;
                        });
            }

            @Override
            public List<V> defaultValue() {
                return new ArrayList<>();
            }

            @Override
            public <V2 extends List<V>> List<V> insert(V2 valueInMap, O object) {
                return combiner.apply(valueInMap, val.getValue(object));
            }

            @Override
            public CondensedType<List<V>, ?> createType(
                    CondensedOutputStream out, EventType eventType) {
                return out.writeAndStoreType(
                        id ->
                                new ArrayType<>(
                                        id,
                                        val.toString() + "[]",
                                        "",
                                        val.createType(out, eventType)));
            }
        }

        /** Map of values as an entry of a map, represented as a list of (key, value) tuples */
        record MapValue<O, K, V>(
                MapPartValue<O, ? extends K> key,
                MapEntry<O, V> value,
                Function<Map<K, V>, List<Entry<K, V>>> mapToList)
                implements MapEntry<O, Map<K, V>> {

            MapValue(MapPartValue<O, ? extends K> key, MapEntry<O, V> value) {
                this(key, value, map -> new ArrayList<>(map.entrySet()));
            }

            @Override
            public Map<K, V> defaultValue() {
                return new HashMap<>();
            }

            @Override
            public <V2 extends Map<K, V>> V2 insert(V2 map, O object) {
                var keyValue = key.getValue(object);
                if (!map.containsKey(keyValue)) {
                    map.put(keyValue, value.insert(value.defaultValue(), object));
                } else {
                    var oldValue = map.get(keyValue);
                    V newValue = value.insert(oldValue, object);
                    if (newValue != oldValue) {
                        map.put(keyValue, newValue);
                    }
                }
                return map;
            }

            @Override
            @SuppressWarnings("unchecked")
            public CondensedType<Map<K, V>, ?> createType(
                    CondensedOutputStream out, EventType eventType) {
                // a map type is essentially a list of key-value pairs
                StructType<Map.Entry<K, V>, Object> pairType =
                        out.writeAndStoreType(
                                id ->
                                        new StructType<>(
                                                id,
                                                "{" + key.toString() + "," + value.toString() + "}",
                                                List.of(
                                                        new Field<Map.Entry<K, V>, Object, Object>(
                                                                "key",
                                                                "",
                                                                (CondensedType<?, Object>)
                                                                        key.createType(
                                                                                out, eventType),
                                                                Entry::getKey),
                                                        new Field<>(
                                                                "value",
                                                                "",
                                                                (CondensedType<?, Object>)
                                                                        value.createType(
                                                                                out, eventType),
                                                                Entry::getValue))));
                return out.writeAndStoreType(
                        id -> new WrappedArrayType<>(new ArrayType<>(id, pairType), mapToList));
            }

            @Override
            public String toString() {
                return "{" + "key=" + key + ", value=" + value + '}';
            }
        }

        /**
         * Insert the object into the map
         *
         * @param valueInMap value that is already in the map
         * @param object object to get the value from
         * @return new value to insert into the map
         */
        <V2 extends V> V insert(V2 valueInMap, O object);
    }

    /** Implementations have to implement the type of their underlying value */
    public sealed interface DefinedObject<O> {
        /** Insert the object into the map */
        void insert(O object);
    }

    /** Root of the {@link MapValue} hierarchy with the associated map */
    static final class DefinedMap<O> extends AbstractMap<Object, Object>
            implements DefinedObject<O> {

        private final MapValue<O, ?, ?> valueDefinition;
        private final Map<?, ?> map = new HashMap<>();

        public DefinedMap(MapValue<O, ?, ?> valueDefinition) {
            this.valueDefinition = valueDefinition;
        }

        public DefinedMap(MapValue<O, ?, ?> valueDefinition, O initial) {
            this(valueDefinition);
            insert(initial);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public void insert(O object) {
            valueDefinition.insert((Map) map, object);
        }

        public Map<?, ?> getMap() {
            return map;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public @NotNull Set<Entry<Object, Object>> entrySet() {
            return (Set<Entry<Object, Object>>) (Set) map.entrySet();
        }
    }

    /**
     * Root of the {@link ArrayValue} hierarchy with the associated list
     *
     * @param <O> type of the object that is inserted
     */
    static final class DefinedArray<O> extends AbstractList<Object> implements DefinedObject<O> {

        private final ArrayValue<O, ?> valueDefinition;
        private final List<?> list = new ArrayList<>();

        public DefinedArray(ArrayValue<O, ?> valueDefinition) {
            this.valueDefinition = valueDefinition;
        }

        public DefinedArray(ArrayValue<O, ?> valueDefinition, O initial) {
            this(valueDefinition);
            insert(initial);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public void insert(O object) {
            valueDefinition.insert((List) list, object);
        }

        @Override
        public Object get(int index) {
            return list.get(index);
        }

        @Override
        public int size() {
            return list.size();
        }
    }

    public interface JFRObjectState {
        Instant startTime();

        DefinedObject<RecordedEvent> map();
    }

    /**
     * A combiner of JFR events, that combines events according to a {@link MapValue} hierarchy
     *
     * @param <T> type of the token that is used to identify the state
     * @param <S> type of the state
     */
    public abstract static class AbstractCombiner<T, S extends JFRObjectState>
            implements Combiner<T, S> {

        final String typeName;
        final Configuration configuration;
        final BasicJFRWriter basicJFRWriter;
        private final MapEntry<RecordedEvent, ?> valueDefinition;
        private final String topLevelFieldName;

        public AbstractCombiner(
                String typeName,
                Configuration configuration,
                BasicJFRWriter basicJFRWriter,
                MapEntry<RecordedEvent, ?> valueDefinition) {
            this(
                    typeName,
                    configuration,
                    basicJFRWriter,
                    getDefaultFieldName(valueDefinition),
                    valueDefinition);
        }

        private static String getDefaultFieldName(MapEntry<?, ?> valueDefinition) {
            if (valueDefinition instanceof MapEntry.SingleValue<?, ?>) {
                return ((SingleValue<?, ?>) valueDefinition).val.name;
            } else if (valueDefinition instanceof MapEntry.ArrayValue<?, ?>) {
                return ((ArrayValue<?, ?>) valueDefinition).val.name;
            } else if (valueDefinition instanceof MapEntry.MapValue<?, ?, ?>) {
                return ((MapValue<?, ?, ?>) valueDefinition).key.name;
            } else {
                throw new IllegalArgumentException(
                        "Unknown value definition type " + valueDefinition);
            }
        }

        /**
         * @param typeName name of the combined event type
         */
        public AbstractCombiner(
                String typeName,
                Configuration configuration,
                BasicJFRWriter basicJFRWriter,
                String topLevelFieldName,
                MapEntry<RecordedEvent, ?> valueDefinition) {
            this.typeName = typeName;
            this.configuration = configuration;
            this.basicJFRWriter = basicJFRWriter;
            this.valueDefinition = valueDefinition;
            this.topLevelFieldName = topLevelFieldName;
        }

        public Instant getStartTimestamp(S state) {
            return state.startTime();
        }

        public List<Field<S, ?, ?>> getAdditionalFields(EventType eventType) {
            return List.of();
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public StructType<S, ?> createCombinedStateType(
                CondensedOutputStream out, EventType eventType) {

            List<Field<S, ?, ?>> fields =
                    new ArrayList<>(
                            List.of(
                                    new Field<>(
                                            "startTime",
                                            basicJFRWriter.getDescription(
                                                    eventType.getField("startTime")),
                                            (CondensedType<Instant, Instant>)
                                                    basicJFRWriter.getTypeCached(
                                                            eventType.getField("startTime")),
                                            s ->
                                                    JFRReduction.TIMESTAMP_REDUCTION.reduce(
                                                            configuration,
                                                            basicJFRWriter.universe,
                                                            getStartTimestamp(s))),
                                    new Field<S, Object, Object>(
                                            topLevelFieldName,
                                            basicJFRWriter.getDescription(
                                                    eventType.getField(topLevelFieldName)),
                                            (CondensedType)
                                                    valueDefinition.createType(out, eventType),
                                            JFRObjectState::map)));
            fields.addAll(getAdditionalFields(eventType));

            return out.writeAndStoreType(id -> new StructType<>(id, typeName, fields));
        }

        @Override
        public void combine(T token, S state, RecordedEvent event) {
            state.map().insert(event);
        }

        @SuppressWarnings("unchecked")
        public <D extends MapEntry<RecordedEvent, ?>> D getValueDefinition() {
            return (D) valueDefinition;
        }
    }

    /** Construct a typed valued event from it's fields */
    public abstract static class EventBuilder<E, S extends EventBuilder<E, S>> {
        private final ReadStruct combinedReadEvent;
        private final String eventTypeName;
        private final Map<String, Object> map;

        public EventBuilder(ReadStruct combinedReadEvent, String eventTypeName) {
            this.eventTypeName = eventTypeName;
            this.combinedReadEvent = combinedReadEvent;
            this.map = new HashMap<>();
            this.map.put("startTime", combinedReadEvent.get("startTime"));
        }

        @SuppressWarnings("unchecked")
        private S thisBuilder() {
            return (S) this;
        }

        public S put(String field, Object value) {
            map.put(field, normalize(value));
            return thisBuilder();
        }

        public S put(String keyField, String valueField, Map.Entry<?, ?> value) {
            put(keyField, value.getKey());
            put(valueField, value.getValue());
            return thisBuilder();
        }

        public S put(String field1, Object value1, String field2, Object value2) {
            put(field1, value1);
            put(field2, value2);
            return thisBuilder();
        }

        public S put(
                String field1,
                Object value1,
                String field2,
                Object value2,
                String field3,
                Object value3) {
            put(field1, value1);
            put(field2, value2);
            put(field3, value3);
            return thisBuilder();
        }

        public S put(
                String field1,
                Object value1,
                String field2,
                Object value2,
                String field3,
                Object value3,
                String field4,
                Object value4) {
            put(field1, value1);
            put(field2, value2);
            put(field3, value3);
            put(field4, value4);
            return thisBuilder();
        }

        /**
         * Put a value from the combined event into the map
         *
         * @param field field name in both the combined event and the result event
         */
        public S put(String field) {
            put(field, combinedReadEvent.get(field));
            return thisBuilder();
        }

        /**
         * Add standard fields (duration, stackTrace and eventThread) if they are missing and are
         * present in the result event type. If the combined event does not contain one of the
         * fields, then set its value to 0/null
         */
        public S addStandardFieldsIfNeeded() {
            for (var fieldName : List.of("duration", "stackTrace", "eventThread")) {
                if (!map.containsKey(fieldName)) {
                    if (combinedReadEvent.hasField(fieldName)) {
                        map.put(fieldName, combinedReadEvent.get(fieldName));
                    } else if (fieldName.equals("duration")) {
                        map.put(fieldName, 0L);
                    } else {
                        map.put(fieldName, null);
                    }
                }
            }
            return thisBuilder();
        }

        public abstract E build();

        public String getEventTypeName() {
            return eventTypeName;
        }

        Map<String, Object> getMap() {
            return map;
        }

        @SuppressWarnings("unchecked")
        StructType<?, ReadStruct> checkType(CondensedType<?, ?> condensedType) {
            if (condensedType == null) {
                throw new IllegalArgumentException("Type " + eventTypeName + " not found");
            }
            var missingFields =
                    ((StructType<?, ?>) condensedType)
                            .getFields().stream()
                                    .map(Field::name)
                                    .filter(f -> !getMap().containsKey(f))
                                    .toList();
            if (!missingFields.isEmpty()) {
                if (missingFields.contains("duration")
                        || missingFields.contains("stackTrace")
                        || missingFields.contains("eventThread")) {
                    throw new IllegalArgumentException(
                            "Fields "
                                    + missingFields
                                    + " are missing in reconstitued event "
                                    + eventTypeName
                                    + " maybe call addStandardFieldsIfNeeded() before build()");
                }
                throw new IllegalArgumentException(
                        "Fields "
                                + missingFields
                                + " are missing in reconstitued event "
                                + eventTypeName);
            }
            return (StructType<?, ReadStruct>) condensedType;
        }
    }

    /** A reconstitutor of JFR events, the inverse of {@link JFREventCombiner.AbstractCombiner} */
    public abstract static class AbstractReconstitutor<C extends AbstractCombiner<?, ?>> {

        private final String eventTypeName;

        /**
         * Creates a new instance
         *
         * @param eventTypeName type name of the resulting event
         */
        public AbstractReconstitutor(String eventTypeName) {
            this.eventTypeName = eventTypeName;
            if (eventTypeName.contains(".combined.")) {
                throw new AssertionError(
                        "Don't use the combined event type name here, try "
                                + eventTypeName.replace(".combined.", "."));
            }
        }

        public String getEventTypeName() {
            return eventTypeName;
        }

        public Reconstitutor<C, TypedValue> createTypedValueReconstitutor(
                WritingJFRReader jfrWriter) {
            return new Reconstitutor<>() {
                @Override
                public String getEventTypeName() {
                    return eventTypeName;
                }

                @Override
                public List<TypedValue> reconstitute(
                        StructType<?, ?> resultEventType, ReadStruct combinedReadEvent) {
                    return AbstractReconstitutor.this.reconstitute(
                            resultEventType,
                            combinedReadEvent,
                            new TypedValueEventBuilder(
                                    combinedReadEvent, eventTypeName, jfrWriter));
                }
            };
        }

        public Reconstitutor<C, ReadStruct> createReadStructReconstitutor(
                TypeCollection typeCollection) {
            return new Reconstitutor<>() {
                @Override
                public String getEventTypeName() {
                    return eventTypeName;
                }

                @Override
                public List<ReadStruct> reconstitute(
                        StructType<?, ?> resultEventType, ReadStruct combinedReadEvent) {
                    return AbstractReconstitutor.this.reconstitute(
                            resultEventType,
                            combinedReadEvent,
                            new ReadStructEventBuilder(
                                    combinedReadEvent, eventTypeName, typeCollection));
                }
            };
        }

        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            return List.of();
        }

        /** Construct a typed valued event from it's fields */
        public static class TypedValueEventBuilder
                extends EventBuilder<TypedValue, TypedValueEventBuilder> {

            private final WritingJFRReader jfrWriter;

            public TypedValueEventBuilder(
                    ReadStruct combinedReadEvent,
                    String eventTypeName,
                    WritingJFRReader jfrWriter) {
                super(combinedReadEvent, eventTypeName);
                this.jfrWriter = jfrWriter;
            }

            @Override
            public TypedValue build() {
                var condensedType = jfrWriter.getCondensedType(getEventTypeName());
                ReadStruct reconstructed = new ReadStruct(checkType(condensedType), getMap());
                return jfrWriter.fromReadStruct(reconstructed);
            }
        }

        /** Construct a typed valued event from it's fields */
        public static class ReadStructEventBuilder
                extends EventBuilder<ReadStruct, ReadStructEventBuilder> {

            private final TypeCollection typeCollection;

            public ReadStructEventBuilder(
                    ReadStruct combinedReadEvent,
                    String eventTypeName,
                    TypeCollection typeCollection) {
                super(combinedReadEvent, eventTypeName);
                this.typeCollection = typeCollection;
            }

            @Override
            public ReadStruct build() {
                var condensedType = typeCollection.getTypeOrNull(getEventTypeName());
                return new ReadStruct(checkType(condensedType), getMap());
            }
        }
    }

    record GCIdToken(long gcId) {}

    record GCIdState(Instant startTime, long gcId, JFREventCombiner.DefinedMap<RecordedEvent> map)
            implements JFREventCombiner.JFRObjectState {}

    /** GC id based combiner */
    abstract static class GCIdBasedCombiner
            extends JFREventCombiner.AbstractCombiner<GCIdToken, GCIdState> {

        public GCIdBasedCombiner(
                String typeName,
                Configuration configuration,
                BasicJFRWriter basicJFRWriter,
                MapValue<RecordedEvent, ?, ?> valueDefinition) {
            super(typeName, configuration, basicJFRWriter, valueDefinition);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Field<GCIdState, ?, ?>> getAdditionalFields(EventType eventType) {
            return List.of(
                    new Field<>(
                            "gcId",
                            "",
                            (CondensedType<Long, Long>)
                                    basicJFRWriter.getTypeCached(eventType.getField("gcId")),
                            s -> s.gcId));
        }

        @Override
        public GCIdState createInitialState(GCIdToken token, RecordedEvent event) {
            var state = new DefinedMap<>(getValueDefinition(), event);
            return new GCIdState(event.getStartTime(), token.gcId(), state);
        }

        @Override
        public GCIdToken createToken(RecordedEvent event) {
            return new GCIdToken(event.getLong("gcId"));
        }
    }

    // TODO

    //  sumObjectSizes sum object sizes in jdk.ObjectAllocationInNewTLAB,
    // *     jdk.ObjectAllocationOutsideTLAB and ObjectAllocation events

    static class BasicObjectAllocationCombiner {
        // per second / configurable
        // thread -> tlab size -> class of object -> array of sizes or summed size
    }

    /** Throws away the thread id and the PLAB size */
    static class PromoteObjectCombiner extends GCIdBasedCombiner {

        public PromoteObjectCombiner(
                String typeName,
                boolean hasPlabSize, // TODO: use hasPlabSize
                Configuration configuration,
                BasicJFRWriter basicJFRWriter) {
            super(
                    typeName,
                    configuration,
                    basicJFRWriter,
                    createValueDefinition(basicJFRWriter, configuration));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static MapValue<RecordedEvent, ?, ?> createValueDefinition(
                BasicJFRWriter basicJFRWriter, Configuration configuration) {

            // class of object -> (tenured ? 64 : 0) + tenuring age -> (array of sizes or summed
            // size)

            BiFunction<CondensedOutputStream, EventType, CondensedType<Object, Object>>
                    objectSizeCreator =
                            (out, eventType) ->
                                    (CondensedType<Object, Object>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("objectSize"));
            JFREventCombiner.MapEntry<RecordedEvent, Object> objectsMapValue =
                    configuration.sumObjectSizes()
                            ? new SingleValue<RecordedEvent, Object>(
                                    new JFREventCombiner.MapPartValue<>(
                                            "objectSize",
                                            objectSizeCreator,
                                            e -> e.getLong("objectSize")),
                                    (a, b) -> (long) a + (long) b,
                                    () -> 0L)
                            : (MapEntry<RecordedEvent, Object>)
                                    (MapEntry)
                                            new ArrayValue<RecordedEvent, Object>(
                                                    new MapPartValue<>(
                                                            "objectSize",
                                                            objectSizeCreator,
                                                            e -> e.getLong("objectSize")));

            return new MapValue<RecordedEvent, Object, Object>(
                    new MapPartValue<>(
                            "objectClass",
                            (out, eventType) ->
                                    (CondensedType<RecordedClass, RecordedClass>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("objectClass")),
                            e -> e.getClass("objectClass")),
                    (MapValue)
                            new MapValue<>(
                                    new MapPartValue<>(
                                            "tenuredAndAge",
                                            (out, eventType) ->
                                                    out.writeAndStoreType(
                                                            mid ->
                                                                    new IntType(
                                                                            mid,
                                                                            "tenuredAndAge",
                                                                            "",
                                                                            1,
                                                                            false,
                                                                            OverflowMode.ERROR)),
                                            e ->
                                                    (e.getBoolean("tenured") ? 64 : 0)
                                                            + e.getLong("tenuringAge")),
                                    objectsMapValue));
        }
    }

    static class PromoteObjectReconstitutor extends AbstractReconstitutor<PromoteObjectCombiner> {

        public PromoteObjectReconstitutor(String combinedEventTypeName) {
            super(combinedEventTypeName);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.addStandardFieldsIfNeeded().put("gcId");
            if (resultEventType.hasField("plabSize")) {
                builder.put("plabSize", -1L);
            }
            return combinedReadEvent.asMapEntryList("objectClass").stream()
                    .flatMap(
                            e -> {
                                builder.put("objectClass", e.getKey());
                                return ((ReadList<?>) e.getValue())
                                        .asMapEntryList().stream()
                                                .flatMap(
                                                        tae -> {
                                                            var tenAndAge = (long) tae.getKey();
                                                            var tenured = tenAndAge >= 64;
                                                            var tenuringAge = tenAndAge % 64;
                                                            builder.put("tenured", tenured);
                                                            builder.put("tenuringAge", tenuringAge);
                                                            if (tae.getValue() instanceof Long) {
                                                                return Stream.of(
                                                                        builder.put(
                                                                                        "objectSize",
                                                                                        tae
                                                                                                .getValue())
                                                                                .build());
                                                            } else {
                                                                return ((ReadList<Long>)
                                                                                tae.getValue())
                                                                        .stream()
                                                                                .map(
                                                                                        s ->
                                                                                                builder.put(
                                                                                                                "objectSize",
                                                                                                                s)
                                                                                                        .build());
                                                            }
                                                        });
                            })
                    .toList();
        }
    }

    /** Throws away the thread id and the PLAB size */
    static class TenuringDistributionCombiner extends GCIdBasedCombiner {

        public TenuringDistributionCombiner(
                String typeName, Configuration configuration, BasicJFRWriter basicJFRWriter) {
            super(
                    typeName,
                    configuration,
                    basicJFRWriter,
                    createValueDefinition(basicJFRWriter, configuration));
        }

        @SuppressWarnings("unchecked")
        private static MapValue<RecordedEvent, ?, ?> createValueDefinition(
                BasicJFRWriter basicJFRWriter, Configuration configuration) {

            // age -> distribution size

            BiFunction<CondensedOutputStream, EventType, CondensedType<Long, Long>>
                    objectSizeCreator =
                            (out, eventType) ->
                                    (CondensedType<Long, Long>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("size"));
            return new MapValue<>(
                    new MapPartValue<>(
                            "age",
                            (out, eventType) -> out.writeAndStoreType(VarIntType::new),
                            e -> e.getLong("age")),
                    new SingleValue<>(
                            new MapPartValue<>("size", objectSizeCreator, e -> e.getLong("size")),
                            Long::sum,
                            () -> 0L),
                    map ->
                            configuration.ignoreZeroSizedTenuredAges()
                                    ? map.entrySet().stream()
                                            .filter(e -> e.getValue() != 0L)
                                            .toList()
                                    : new ArrayList<>(map.entrySet()));
        }
    }

    static class TenuringDistributionReconstitutor
            extends AbstractReconstitutor<TenuringDistributionCombiner> {

        public TenuringDistributionReconstitutor() {
            super("jdk.TenuringDistribution");
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.put("gcId").addStandardFieldsIfNeeded();
            return combinedReadEvent.asMapEntryList("age").stream()
                    .map(e -> builder.put("age", "size", e).build())
                    .toList();
        }
    }

    /** Combines per GC id, throws away the thread id */
    static class GCPhasePauseLevelCombiner extends GCIdBasedCombiner {

        public GCPhasePauseLevelCombiner(
                String typeName, Configuration configuration, BasicJFRWriter basicJFRWriter) {
            super(
                    typeName,
                    configuration,
                    basicJFRWriter,
                    createValueDefinition(basicJFRWriter, configuration));
        }

        @SuppressWarnings("unchecked")
        private static MapValue<RecordedEvent, ?, ?> createValueDefinition(
                BasicJFRWriter basicJFRWriter, Configuration configuration) {

            // phase name -> duration

            BiFunction<CondensedOutputStream, EventType, CondensedType<Long, Long>>
                    durationCreator = (out, eventType) -> basicJFRWriter.getDurationType();

            return new MapValue<>(
                    new MapPartValue<>(
                            "name",
                            (out, eventType) ->
                                    (CondensedType<String, String>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("name")),
                            e -> e.getString("name")),
                    new SingleValue<>(
                            new MapPartValue<>(
                                    "duration",
                                    durationCreator,
                                    e -> clamp(e.getDuration("duration")).toNanos())),
                    map ->
                            configuration.ignoreTooShortGCPauses()
                                    ? map.entrySet().stream()
                                            .filter(
                                                    e ->
                                                            !basicJFRWriter
                                                                    .isEffectivelyZeroDuration(
                                                                            e.getValue()))
                                            .toList()
                                    : new ArrayList<>(map.entrySet()));
        }
    }

    static class GCPhasePauseLevelReconstitutor
            extends AbstractReconstitutor<JFREventCombiner.GCPhasePauseLevelCombiner> {
        public GCPhasePauseLevelReconstitutor(String eventTypeName) {
            super(eventTypeName);
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.put("gcId").addStandardFieldsIfNeeded();
            return combinedReadEvent.asMapEntryList("name").stream()
                    .map(e -> builder.put("name", "duration", e).build())
                    .toList();
        }
    }

    /** Combines per GC id */
    static class GCPhaseParallelCombiner extends GCIdBasedCombiner {

        public GCPhaseParallelCombiner(Configuration configuration, BasicJFRWriter basicJFRWriter) {
            super(
                    "jdk.combined.GCPhaseParallel",
                    configuration,
                    basicJFRWriter,
                    createValueDefinition(basicJFRWriter, configuration));
        }

        @SuppressWarnings("unchecked")
        private static MapValue<RecordedEvent, ?, ?> createValueDefinition(
                BasicJFRWriter basicJFRWriter, Configuration configuration) {

            // name -> (GC Thread + worker identifier + duration)
            // spotless:off
            var gcWorkerDuration = new MapPartValue<RecordedEvent, RecordedEvent>(
                    "gcworkerDuration",
                    (out, eventType) -> (CondensedType) basicJFRWriter.getOutputStream().writeAndStoreType(id -> {
                        return new StructType<RecordedEvent, ReadStruct>(id, "GCWorker", List.of(
                                basicJFRWriter.eventFieldToField(eventType.getField("eventThread"), true),
                                basicJFRWriter.eventFieldToField(eventType.getField("gcWorkerId"), true),
                                basicJFRWriter.eventFieldToField(eventType.getField("duration"), true)));
                    }),
                    e -> e);
            // spotless:on
            return new MapValue<>(
                    new MapPartValue<>(
                            "name",
                            (out, eventType) ->
                                    (CondensedType<String, String>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("name")),
                            e -> e.getString("name")),
                    new SingleValue<>(gcWorkerDuration),
                    map -> new ArrayList<>(map.entrySet()));
        }
    }

    static class GCPhaseParallelReconstitutor
            extends AbstractReconstitutor<JFREventCombiner.GCPhaseParallelCombiner> {
        public GCPhaseParallelReconstitutor() {
            super("jdk.GCPhaseParallel");
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.put("gcId").addStandardFieldsIfNeeded();
            return combinedReadEvent.asMapEntryList("name").stream()
                    .map(
                            e -> {
                                ReadStruct struct = (ReadStruct) e.getValue();
                                return builder.put("name", e.getKey())
                                        .put("eventThread", struct.get("eventThread"))
                                        .put("gcWorkerId", struct.get("gcWorkerId"))
                                        .put("duration", struct.get("duration"))
                                        .build();
                            })
                    .toList();
        }
    }

    record ObjectAllocationSampleToken(long nextGCId) {}

    static final class ObjectAllocationSampleState implements JFRObjectState {
        private final long nextGCId;
        private final DefinedMap<RecordedEvent> map;
        private final Instant startTime;
        private Instant endTimestamp;

        ObjectAllocationSampleState(
                long nextGCId, DefinedMap<RecordedEvent> map, Instant startTimestamp) {
            this.nextGCId = nextGCId;
            this.map = map;
            this.startTime = startTimestamp;
            this.endTimestamp = startTimestamp;
        }

        @Override
        public DefinedMap<RecordedEvent> map() {
            return map;
        }

        public void updateEndTimestamp(Instant endTimestamp) {
            this.endTimestamp =
                    endTimestamp.compareTo(this.endTimestamp) > 0
                            ? endTimestamp
                            : this.endTimestamp;
        }

        @Override
        public String toString() {
            return "ObjectAllocationSampleState["
                    + "nextGCId="
                    + nextGCId
                    + ", "
                    + "map="
                    + map
                    + ", "
                    + "startTimestamp="
                    + startTime
                    + ']';
        }

        @Override
        public Instant startTime() {
            return startTime;
        }
    }

    /** Throws away the TLAB size and the thread id if available */
    static class ObjectAllocationSampleCombiner
            extends AbstractCombiner<ObjectAllocationSampleToken, ObjectAllocationSampleState> {
        private final GCIdPerTimestamp gcIdPerTimestamp;

        public ObjectAllocationSampleCombiner(
                String typeName,
                Configuration configuration,
                BasicJFRWriter basicJFRWriter,
                GCIdPerTimestamp gcIdPerTimestamp) {
            super(
                    typeName,
                    configuration,
                    basicJFRWriter,
                    createValueDefinition(basicJFRWriter, configuration));
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

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static MapValue<RecordedEvent, ?, ?> createValueDefinition(
                BasicJFRWriter basicJFRWriter, Configuration configuration) {

            // class -> (array of sizes or summed size)

            BiFunction<CondensedOutputStream, EventType, CondensedType<Object, Object>>
                    objectSizeCreator =
                            (out, eventType) ->
                                    (CondensedType<Object, Object>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("weight"));

            MapEntry<RecordedEvent, Object> objectsMapValue =
                    configuration.sumObjectSizes()
                            ? new SingleValue<RecordedEvent, Object>(
                                    new MapPartValue<>(
                                            "objectSize",
                                            objectSizeCreator,
                                            e -> e.getLong("weight")),
                                    (a, b) -> (long) a + (long) b,
                                    () -> 0L)
                            : (MapEntry<RecordedEvent, Object>)
                                    (MapEntry)
                                            new ArrayValue<RecordedEvent, Object>(
                                                    new MapPartValue<>(
                                                            "weight",
                                                            objectSizeCreator,
                                                            e -> e.getLong("weight")));

            return new MapValue<RecordedEvent, Object, Object>(
                    new MapPartValue<>(
                            "objectClass",
                            (out, eventType) ->
                                    (CondensedType<RecordedClass, RecordedClass>)
                                            basicJFRWriter.getTypeCached(
                                                    eventType.getField("objectClass")),
                            e -> e.getClass("objectClass")),
                    objectsMapValue);
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

    static class ObjectAllocationSampleReconstitutor
            extends AbstractReconstitutor<ObjectAllocationSampleCombiner> {

        public ObjectAllocationSampleReconstitutor() {
            super("jdk.ObjectAllocationSample");
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.put("endTime").addStandardFieldsIfNeeded();
            return combinedReadEvent.asMapEntryList("objectClass").stream()
                    .map(e -> builder.put("objectClass", "weight", e).build())
                    .toList();
        }
    }

    enum PSHeapSummaryWhen {
        BEFORE,
        AFTER
    }

    private final Configuration configuration;
    private final @Nullable BasicJFRWriter basicJFRWriter;
    private final GCIdPerTimestamp gcIdPerTimestamp;
    private final Map<String, Boolean> hasEventGCField = new HashMap<>();

    /** Cache size that is enough to make the effect of event reordering negligible */
    public static final int DEFAULT_CACHE_SIZE = 10;

    public JFREventCombiner(
            CondensedOutputStream out,
            Configuration configuration,
            @Nullable BasicJFRWriter basicJFRWriter) {
        this(out, configuration, basicJFRWriter, DEFAULT_CACHE_SIZE);
    }

    public JFREventCombiner(
            CondensedOutputStream out,
            Configuration configuration,
            @Nullable BasicJFRWriter basicJFRWriter,
            int cacheSize) {
        super(out, cacheSize);
        this.configuration = configuration;
        this.basicJFRWriter = basicJFRWriter;
        this.gcIdPerTimestamp = new GCIdPerTimestamp();
    }

    @Override
    public boolean processEvent(RecordedEvent event) {
        // capture gc information
        var hasGCId =
                hasEventGCField.computeIfAbsent(
                        event.getEventType().getName(),
                        n -> event.getEventType().getField("gcId") != null);
        if (hasGCId) {
            // record the GC ID for the timestamp
            gcIdPerTimestamp.put(event.getStartTime(), event.getLong("gcId"));
        }
        return super.processEvent(event);
    }

    /**
     * Emits the state wrapper header object message and then afterwards the state object message
     */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void stateWriter(StructType<?, ?> type, Object state) {
        out.writeMessage((StructType) type, state);
    }

    public void putIfNotThere(EventType eventType, Supplier<Combiner<?, ?>> combinerSupplier) {
        super.putIfNotThere(
                eventType,
                combinerSupplier,
                () -> {
                    if (basicJFRWriter != null) {
                        basicJFRWriter.writeOutEventTypeIfNeeded(eventType);
                    }
                });
    }

    public void put(EventType eventType, Combiner<?, ?> combiner) {
        super.put(
                eventType,
                combiner,
                () -> {
                    if (basicJFRWriter != null) {
                        basicJFRWriter.writeOutEventTypeIfNeeded(eventType);
                    }
                });
    }

    @Override
    void processNewEventType(EventType eventType) {
        if (!configuration.eventCombinersEnabled()) {
            return;
        }
        if (configuration.combinePLABPromotionEvents()) {
            String name = null;
            boolean hasPlabSize = false;
            if (eventType.getName().equals("jdk.PromoteObjectInNewPLAB")) {
                name = "jdk.combined.PromoteObjectInNewPLAB";
                hasPlabSize = true;
            } else if (eventType.getName().equals("jdk.PromoteObjectOutsidePLAB")) {
                name = "jdk.combined.PromoteObjectOutsidePLAB";
            }
            if (name != null) {
                put(
                        eventType,
                        new JFREventCombiner.PromoteObjectCombiner(
                                name, hasPlabSize, configuration, basicJFRWriter));
            }
        }
        if (configuration.combineObjectAllocationSampleEvents()) {
            if (eventType.getName().equals("jdk.ObjectAllocationSample")) {
                put(
                        eventType,
                        new JFREventCombiner.ObjectAllocationSampleCombiner(
                                "jdk.combined.ObjectAllocationSample",
                                configuration,
                                basicJFRWriter,
                                gcIdPerTimestamp));
            }
        }
        if (configuration.combineEventsWithoutDataLoss()) {
            if (eventType.getName().equals("jdk.TenuringDistribution")) {
                put(
                        eventType,
                        new JFREventCombiner.TenuringDistributionCombiner(
                                "jdk.combined.TenuringDistribution",
                                configuration,
                                basicJFRWriter));
            }
            if (eventType.getName().equals("jdk.GCPhasePauseLevel1")) {
                put(
                        eventType,
                        new JFREventCombiner.GCPhasePauseLevelCombiner(
                                "jdk.combined.GCPhasePauseLevel1", configuration, basicJFRWriter));
            }
            if (eventType.getName().equals("jdk.GCPhasePauseLevel2")) {
                put(
                        eventType,
                        new JFREventCombiner.GCPhasePauseLevelCombiner(
                                "jdk.combined.GCPhasePauseLevel2", configuration, basicJFRWriter));
            }
            if (eventType.getName().equals("jdk.GCPhaseParallel")) {
                put(
                        eventType,
                        new JFREventCombiner.GCPhaseParallelCombiner(
                                configuration, basicJFRWriter));
            }
        }
    }

    private static final Map<String, AbstractReconstitutor<? extends AbstractCombiner<?, ?>>>
            recons =
                    new HashMap<>(
                            Map.ofEntries(
                                    Map.entry(
                                            "jdk.combined.ObjectAllocationSample",
                                            new ObjectAllocationSampleReconstitutor()),
                                    Map.entry(
                                            "jdk.combined.PromoteObjectInNewPLAB",
                                            new PromoteObjectReconstitutor(
                                                    "jdk.PromoteObjectInNewPLAB")),
                                    Map.entry(
                                            "jdk.combined.PromoteObjectOutsidePLAB",
                                            new PromoteObjectReconstitutor(
                                                    "jdk.PromoteObjectOutsidePLAB")),
                                    Map.entry(
                                            "jdk.combined.GCPhasePauseLevel1",
                                            new GCPhasePauseLevelReconstitutor(
                                                    "jdk.GCPhasePauseLevel1")),
                                    Map.entry(
                                            "jdk.combined.GCPhasePauseLevel2",
                                            new GCPhasePauseLevelReconstitutor(
                                                    "jdk.GCPhasePauseLevel2")),
                                    Map.entry(
                                            "jdk.combined.TenuringDistribution",
                                            new TenuringDistributionReconstitutor()),
                                    Map.entry(
                                            "jdk.combined.GCPhaseParallel",
                                            new GCPhaseParallelReconstitutor())));

    public static class JFREventTypedValuedReconstitutor extends EventReconstitutor<TypedValue> {

        public JFREventTypedValuedReconstitutor(WritingJFRReader jfrWriter) {
            super(
                    recons.entrySet().stream()
                            .collect(
                                    Collectors.toMap(
                                            Map.Entry::getKey,
                                            e ->
                                                    e.getValue()
                                                            .createTypedValueReconstitutor(
                                                                    jfrWriter))));
        }
    }

    public static class JFREventReadStructReconstitutor extends EventReconstitutor<ReadStruct> {

        public JFREventReadStructReconstitutor(CondensedInputStream inputStream) {
            super(
                    recons.entrySet().stream()
                            .collect(
                                    Collectors.toMap(
                                            Map.Entry::getKey,
                                            e ->
                                                    e.getValue()
                                                            .createReadStructReconstitutor(
                                                                    inputStream
                                                                            .getTypeCollection()))));
        }
    }
}
