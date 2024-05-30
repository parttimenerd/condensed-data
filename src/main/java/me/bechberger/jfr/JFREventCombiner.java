package me.bechberger.jfr;

import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.FloatType.Type;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.EventCombiner.Combiner;
import me.bechberger.jfr.JFREventCombiner.ObjectSizePlabSizePair;
import me.bechberger.jfr.JFREventCombiner.TenuringAgeTenuredPair;
import org.jetbrains.annotations.Nullable;

public class JFREventCombiner extends EventCombiner {

    record ObjectSizePlabSizePair(long objectSize, long plabSize) {}

    record TenuringAgeTenuredPair(long tenuringAge, boolean tenured) {
        long toByte() {
            if (tenuringAge < 0 || tenuringAge > 127) {
                throw new IllegalArgumentException("Tenuring age must be in range 0-127");
            }
            return tenuringAge | (tenured ? 0b10000000 : 0);
        }

        static TenuringAgeTenuredPair fromByte(byte b) {
            return new TenuringAgeTenuredPair(b & 0b01111111, (b & 0b10000000) != 0);
        }
    }

    static class PromoteObjectState {
        /** jdk.PromoteObjectOutsidePLAB has now plabSize field */
        private final boolean hasPlabSize;

        private final PromoteObjectToken token;
        private final RecordedThread eventThread; // but inaccurate
        private final Instant startTime;
        private final Map<RecordedClass, Map<ObjectSizePlabSizePair, List<TenuringAgeTenuredPair>>>
                tenuringAgeTenuredPairsPerClass = new HashMap<>();

        public PromoteObjectState(
                boolean hasPlabSize,
                PromoteObjectToken token,
                RecordedThread eventThread,
                Instant startTime) {
            this.hasPlabSize = hasPlabSize;
            this.token = token;
            this.eventThread = eventThread;
            this.startTime = startTime;
        }

        public void add(RecordedEvent event) {
            tenuringAgeTenuredPairsPerClass
                    .computeIfAbsent(event.getClass("objectClass"), k -> new HashMap<>())
                    .computeIfAbsent(
                            new ObjectSizePlabSizePair(
                                    event.getLong("objectSize"),
                                    hasPlabSize ? event.getLong("plabSize") : 0),
                            k -> new ArrayList<>())
                    .add(
                            new TenuringAgeTenuredPair(
                                    event.getLong("tenuringAge"), event.getBoolean("tenured")));
        }
    }

    // ignore eventThread and startTime
    record PromoteObjectToken(long gcId) {}

    /**
     * Combiner for jdk.PromoteObjectInNewPLAB and jdk.PromoteObjectOutsidePLAB
     *
     * <p>Throws away the GC thread information
     */
    static class PromoteObjectCombiner implements Combiner<PromoteObjectToken, PromoteObjectState> {

        private final String typeName;
        private final boolean hasPlabSize;
        private final Configuration configuration;
        private final BasicJFRWriter basicJFRWriter;

        public PromoteObjectCombiner(
                String typeName,
                boolean hasPlabSize,
                Configuration configuration,
                BasicJFRWriter basicJFRWriter) {
            this.typeName = typeName;
            this.hasPlabSize = hasPlabSize;
            this.configuration = configuration;
            this.basicJFRWriter = basicJFRWriter;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public StructType<PromoteObjectState, ?> createCombinedStateType(
                CondensedOutputStream out, EventType eventType) {
            return out.writeAndStoreType(
                    id -> {
                        var largeMemory =
                                configuration.memoryAsBFloat16()
                                        ? out.writeAndStoreType(
                                                mid -> new FloatType(mid, "", "", Type.BFLOAT16))
                                        : out.writeAndStoreType(
                                                mid -> new VarIntType(mid, "", "", false));
                        var smallMemory =
                                out.writeAndStoreType(mid -> new VarIntType(mid, "", "", false));

                        var tenuringAgePairIntType =
                                out.writeAndStoreType(
                                        tid ->
                                                new IntType(
                                                        tid,
                                                        "tenuring",
                                                        "",
                                                        1,
                                                        false,
                                                        OverflowMode.ERROR));
                        var tenuringAgePairType =
                                out.writeAndStoreType(
                                        tid ->
                                                new StructType<TenuringAgeTenuredPair, ReadStruct>(
                                                        tid,
                                                        "{tenuring}",
                                                        List.of(
                                                                new Field<
                                                                        TenuringAgeTenuredPair,
                                                                        Object,
                                                                        Long>(
                                                                        "ten",
                                                                        "",
                                                                        tenuringAgePairIntType,
                                                                        TenuringAgeTenuredPair
                                                                                ::toByte))));
                        var tenuringAgePairArrayType =
                                out.writeAndStoreType(
                                        tid ->
                                                new ArrayType<>(
                                                        tid,
                                                        "[tenuring]",
                                                        "",
                                                        tenuringAgePairType));

                        List<
                                        Field<
                                                Entry<
                                                        ObjectSizePlabSizePair,
                                                        List<TenuringAgeTenuredPair>>,
                                                ?,
                                                ?>>
                                fields =
                                        new ArrayList<>(
                                                List.of(
                                                        new Field<
                                                                Map.Entry<
                                                                        ObjectSizePlabSizePair,
                                                                        List<
                                                                                TenuringAgeTenuredPair>>,
                                                                Object,
                                                                Long>(
                                                                "objectSize",
                                                                "",
                                                                smallMemory,
                                                                e -> e.getKey().objectSize()),
                                                        new Field<
                                                                Map.Entry<
                                                                        ObjectSizePlabSizePair,
                                                                        List<
                                                                                TenuringAgeTenuredPair>>,
                                                                Object,
                                                                Object>(
                                                                "tenuringAgeTenuredPairs",
                                                                "",
                                                                (CondensedType)
                                                                        tenuringAgePairArrayType,
                                                                Entry::getValue)));

                        if (hasPlabSize) {
                            fields.add(
                                    new Field<
                                            Map.Entry<
                                                    ObjectSizePlabSizePair,
                                                    List<TenuringAgeTenuredPair>>,
                                            Object,
                                            Object>(
                                            "plabSize",
                                            "",
                                            (CondensedType) largeMemory,
                                            e ->
                                                    configuration.memoryAsBFloat16()
                                                            ? (float) e.getKey().plabSize()
                                                            : e.getKey().plabSize()));
                        }

                        var tenuringAgePerOPlabType =
                                out.writeAndStoreType(
                                        tid ->
                                                new StructType<
                                                        Map.Entry<
                                                                ObjectSizePlabSizePair,
                                                                List<TenuringAgeTenuredPair>>,
                                                        ReadStruct>(
                                                        tid, "{objectSize, ...}", fields));

                        var tenuringAgePerOPlabArrayType =
                                out.writeAndStoreType(
                                        tid ->
                                                new ArrayType<>(
                                                        tid,
                                                        "[objectSize, ...]",
                                                        "",
                                                        tenuringAgePerOPlabType));

                        var tenuringAgePerClassType =
                                out.writeAndStoreType(
                                        tid ->
                                                new StructType<
                                                        Map.Entry<
                                                                RecordedClass,
                                                                Map<
                                                                        ObjectSizePlabSizePair,
                                                                        List<
                                                                                TenuringAgeTenuredPair>>>,
                                                        ReadStruct>(
                                                        tid,
                                                        "{class, ...}",
                                                        List.of(
                                                                new Field<
                                                                        Map.Entry<
                                                                                RecordedClass,
                                                                                Map<
                                                                                        ObjectSizePlabSizePair,
                                                                                        List<
                                                                                                TenuringAgeTenuredPair>>>,
                                                                        Object,
                                                                        Object>(
                                                                        "objectClass",
                                                                        "",
                                                                        (CondensedType<?, Object>)
                                                                                basicJFRWriter
                                                                                        .getTypeCached(
                                                                                                eventType
                                                                                                        .getField(
                                                                                                                "objectClass")),
                                                                        Entry::getKey,
                                                                        EmbeddingType.REFERENCE),
                                                                new Field<
                                                                        Map.Entry<
                                                                                RecordedClass,
                                                                                Map<
                                                                                        ObjectSizePlabSizePair,
                                                                                        List<
                                                                                                TenuringAgeTenuredPair>>>,
                                                                        Object,
                                                                        Object>(
                                                                        "tenuringAgeTenuredPairsPerOPlab",
                                                                        "",
                                                                        (CondensedType)
                                                                                tenuringAgePerOPlabArrayType,
                                                                        e ->
                                                                                e
                                                                                        .getValue()
                                                                                        .entrySet()
                                                                                        .stream()
                                                                                        .toList()))));

                        var tenuringAgePerClassArrayType =
                                out.writeAndStoreType(
                                        tid ->
                                                new ArrayType<>(
                                                        tid,
                                                        "[class,...]",
                                                        "",
                                                        tenuringAgePerClassType));

                        return new StructType<PromoteObjectState, Object>(
                                id,
                                typeName,
                                List.of(
                                        new Field<PromoteObjectState, Object, Instant>(
                                                "startTime",
                                                "",
                                                (CondensedType<Instant, Instant>)
                                                        basicJFRWriter.getTypeCached(
                                                                eventType.getField("startTime")),
                                                s ->
                                                        JFRReduction.TIMESTAMP_REDUCTION.reduce(
                                                                configuration,
                                                                basicJFRWriter.universe,
                                                                s.startTime)),
                                        new Field<PromoteObjectState, Long, Long>(
                                                "gcId",
                                                "",
                                                (CondensedType<Long, Long>)
                                                        basicJFRWriter.getTypeCached(
                                                                eventType.getField("gcId")),
                                                s -> s.token.gcId()),
                                        new Field<
                                                PromoteObjectState, RecordedThread, RecordedThread>(
                                                "eventThread",
                                                "",
                                                (CondensedType<RecordedThread, RecordedThread>)
                                                        basicJFRWriter.getTypeCached(
                                                                eventType.getField("eventThread")),
                                                s -> s.eventThread,
                                                EmbeddingType.REFERENCE),
                                        new Field<PromoteObjectState, Object, Object>(
                                                "tenuringAgePerClass",
                                                "",
                                                (CondensedType) tenuringAgePerClassArrayType,
                                                s ->
                                                        s
                                                                .tenuringAgeTenuredPairsPerClass
                                                                .entrySet()
                                                                .stream()
                                                                .toList())));
                    });
        }

        @Override
        public PromoteObjectState createInitialState(
                PromoteObjectToken token, RecordedEvent event) {
            var state =
                    new PromoteObjectState(
                            hasPlabSize,
                            token,
                            event.getThread("eventThread"),
                            event.getStartTime());
            state.add(event);
            return state;
        }

        @Override
        public void combine(
                PromoteObjectToken token, PromoteObjectState state, RecordedEvent event) {
            state.add(event);
        }

        @Override
        public boolean canClear(PromoteObjectState state, PromoteObjectToken newToken) {
            return state.token.gcId() != newToken.gcId();
        }

        public List<ReadStruct> reconstitute(
                StructType<ReadStruct, ReadStruct> eventType, ReadStruct combinedReadEvent) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public PromoteObjectToken createToken(RecordedEvent event) {
            return new PromoteObjectToken(event.getLong("gcId"));
        }
    }

    private final Configuration configuration;
    private final BasicJFRWriter basicJFRWriter;
    private final String EVENT_STATE_HEADER = "EventStateHeader";
    private final @Nullable StructType<?, ?> eventStateHeaderType;

    public JFREventCombiner(
            CondensedOutputStream out,
            Configuration configuration,
            @Nullable BasicJFRWriter basicJFRWriter) {
        super(out);
        this.configuration = configuration;
        this.basicJFRWriter = basicJFRWriter;
        this.eventStateHeaderType =
                basicJFRWriter != null
                        ? out.writeAndStoreType(
                                id -> {
                                    return new StructType<Object, Object>(
                                            id, EVENT_STATE_HEADER, List.of());
                                })
                        : null;
    }

    /**
     * Emits the state wrapper header object message and then afterwards the state object message
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void stateWriter(StructType<?, ?> type, Object state) {
        if (eventStateHeaderType == null) {
            throw new IllegalStateException("Event state header type not available");
        }
        this.out.writeMessage(eventStateHeaderType, null);
        out.writeMessage((StructType) type, state);
    }

    @Override
    void processNewEventType(EventType eventType) {
        if (!configuration.eventCombinersEnabled()) {
            return;
        }
        if (configuration.combinePLABPromotionEvents()) {
            if (eventType.getName().equals("jdk.PromoteObjectInNewPLAB")) {
                put(
                        eventType,
                        new PromoteObjectCombiner(
                                "PromoteObjectInNewPLAB", true, configuration, basicJFRWriter));
            } else if (eventType.getName().equals("jdk.PromoteObjectOutsidePLAB")) {
                put(
                        eventType,
                        new PromoteObjectCombiner(
                                "PromoteObjectOutsidePLAB", false, configuration, basicJFRWriter));
            }
        }
    }

    @Override
    public boolean isEventStateWrapperType(StructType<?, ?> type) {
        return type.getName().equals(EVENT_STATE_HEADER) && type.getFields().isEmpty();
    }
}
