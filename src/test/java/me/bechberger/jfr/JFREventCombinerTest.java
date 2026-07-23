package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.stats.EventWriteTree;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.condensed.types.TypeCollection;
import me.bechberger.jfr.EventCombiner.Combiner;
import me.bechberger.jfr.JFREventCombiner.*;
import me.bechberger.jfr.JFREventCombiner.MapEntry.ArrayValue;
import me.bechberger.jfr.JFREventCombiner.MapEntry.MapValue;
import me.bechberger.jfr.JFREventCombiner.MapEntry.SingleValue;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;

public class JFREventCombinerTest {

    @Name("EventThatEndsRecording")
    static class EventThatEndsRecording extends Event {
        static void create() {
            EventThatEndsRecording event = new EventThatEndsRecording();
            event.commit();
        }
    }

    @Name("TestCombineToArrayEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace()
    static class TestCombineToArrayEvent extends Event {
        int state;
        int val;

        TestCombineToArrayEvent(int state, int val) {
            this.state = state;
            this.val = val;
        }

        static void create(int state, int val) {
            TestCombineToArrayEvent event = new TestCombineToArrayEvent(state, val);
            event.commit();
        }
    }

    record TestCombineToArrayToken(int state) {}

    record TestCombineToArrayState(Instant startTime, DefinedArray<RecordedEvent> map, int state)
            implements JFRObjectState {}

    static class TestCombineToArrayCombiner
            extends AbstractCombiner<TestCombineToArrayToken, TestCombineToArrayState> {

        public TestCombineToArrayCombiner(
                Configuration configuration, BasicJFRWriter basicJFRWriter) {
            super(
                    "jdk.combined.TestCombineToArray",
                    configuration,
                    basicJFRWriter,
                    "val",
                    createValueDefinition());
        }

        private static ArrayValue<RecordedEvent, ?> createValueDefinition() {
            return new ArrayValue<>(
                    new MapPartValue<>(
                            "val",
                            (o, e) -> TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE),
                            e -> e.getLong("val")));
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Field<TestCombineToArrayState, ?, ?>> getAdditionalFields(EventType eventType) {
            return List.of(
                    new Field<>(
                            "state",
                            "",
                            (CondensedType<Long, Long>)
                                    basicJFRWriter.getTypeCached(eventType.getField("state")),
                            TestCombineToArrayState::state));
        }

        @Override
        public TestCombineToArrayToken createToken(RecordedEvent event) {
            return new TestCombineToArrayToken(event.getInt("state"));
        }

        @Override
        public TestCombineToArrayState createInitialState(
                TestCombineToArrayToken token, RecordedEvent event) {
            return new TestCombineToArrayState(
                    event.getStartTime(),
                    new DefinedArray<>(getValueDefinition(), event),
                    token.state());
        }
    }

    static class TestCombineArrayReconstitutor
            extends AbstractReconstitutor<TestCombineToArrayCombiner> {

        public TestCombineArrayReconstitutor() {
            super("TestCombineToArrayEvent");
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.put("state").addStandardFieldsIfNeeded();
            return combinedReadEvent.getList("val").stream()
                    .map(v -> builder.put("val", v).build())
                    .toList();
        }
    }

    /**
     * Test combining and reconstructing JFR events by using the {@link JFREventCombiner} and
     * implementing a minimal combiner for {@link TestCombineToArrayEvent}
     *
     * <p>State is {@link TestCombineToArrayEvent#state} and {@link TestCombineToArrayEvent#val} is
     * collected in an array
     */
    @Test
    public void testBasicJFREventCombiner() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "TestCombineToArrayEvent",
                                new CombinerAndReconstitutor(
                                        w ->
                                                new TestCombineToArrayCombiner(
                                                        Configuration.DEFAULT, w),
                                        "jdk.combined.TestCombineToArray",
                                        new TestCombineArrayReconstitutor())),
                        () -> {
                            TestCombineToArrayEvent.create(1, 2);
                            TestCombineToArrayEvent.create(1, 3);
                            TestCombineToArrayEvent.create(2, 4);
                            TestCombineToArrayEvent.create(2, 5);
                            TestCombineToArrayEvent.create(3, 6);
                        });
        assertEquals(5, res.recordedEvents.size());
        assertEquals(5, res.readEvents.size());
        assertEquals(3, res.combinedEventCount.get("jdk.combined.TestCombineToArray"));
        for (int i = 0; i < 5; i++) {
            var recordedEvent = res.recordedEvents.get(i);
            var readEvent = res.readEvents.get(i);
            assertEquals(recordedEvent.getInt("state"), TypedValueUtil.get(readEvent, "state"));
            assertEquals(recordedEvent.getInt("val"), TypedValueUtil.get(readEvent, "val"));
        }
    }

    @Name("TestCombineToArrayAndSumEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace()
    static class TestCombineToArrayAndSumEvent extends Event {
        int state;
        int state2;
        int val;

        TestCombineToArrayAndSumEvent(int state, int state2, int val) {
            this.state = state;
            this.state2 = state2;
            this.val = val;
        }

        static void create(int state, int state2, int val) {
            TestCombineToArrayAndSumEvent event =
                    new TestCombineToArrayAndSumEvent(state, state2, val);
            event.commit();
        }
    }

    record TestCombineToArrayAndSumEventToken(int state) {}

    record TestCombineToArrayAndSumEventState(
            Instant startTime, DefinedMap<RecordedEvent> map, int state)
            implements JFRObjectState {}

    static class TestCombineToArrayAndSumEventCombiner
            extends AbstractCombiner<
                    TestCombineToArrayAndSumEventToken, TestCombineToArrayAndSumEventState> {

        public TestCombineToArrayAndSumEventCombiner(
                Configuration configuration, BasicJFRWriter basicJFRWriter) {
            super(
                    "jdk.combined.TestCombineToArrayAndSum",
                    configuration,
                    basicJFRWriter,
                    createValueDefinition());
        }

        private static MapValue<RecordedEvent, ?, ?> createValueDefinition() {
            return new MapValue<>(
                    new MapPartValue<>(
                            "state2",
                            (o, e) -> TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE),
                            e -> e.getLong("state2")),
                    new SingleValue<>(
                            new MapPartValue<>(
                                    "val",
                                    (o, e) ->
                                            TypeCollection.getDefaultTypeInstance(
                                                    IntType.SPECIFIED_TYPE),
                                    e -> e.getLong("val")),
                            Long::sum,
                            () -> 0L));
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Field<TestCombineToArrayAndSumEventState, ?, ?>> getAdditionalFields(
                EventType eventType) {
            return List.of(
                    new Field<>(
                            "state",
                            "",
                            (CondensedType<Long, Long>)
                                    basicJFRWriter.getTypeCached(eventType.getField("state")),
                            TestCombineToArrayAndSumEventState::state));
        }

        @Override
        public TestCombineToArrayAndSumEventToken createToken(RecordedEvent event) {
            return new TestCombineToArrayAndSumEventToken(event.getInt("state"));
        }

        @Override
        public TestCombineToArrayAndSumEventState createInitialState(
                TestCombineToArrayAndSumEventToken token, RecordedEvent event) {
            return new TestCombineToArrayAndSumEventState(
                    event.getStartTime(),
                    new DefinedMap<>(getValueDefinition(), event),
                    token.state());
        }
    }

    static class TestCombineArrayAndSumReconstitutor
            extends AbstractReconstitutor<TestCombineToArrayAndSumEventCombiner> {

        public TestCombineArrayAndSumReconstitutor() {
            super("TestCombineToArrayAndSumEvent");
        }

        @Override
        public <E> List<E> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                EventBuilder<E, ?> builder) {
            builder.put("state").addStandardFieldsIfNeeded();
            return combinedReadEvent.asMapEntryList("state2").stream()
                    .map(v -> builder.put("state2", v.getKey(), "val", v.getValue()).build())
                    .toList();
        }
    }

    /**
     * Test combining and reconstructing JFR events by using the {@link JFREventCombiner} and
     * implementing a minimal combiner for {@link TestCombineToArrayAndSumEvent}
     *
     * <p>Per state and state2, summed of val
     */
    @Test
    public void testBasicJFREventCombinerWithSum() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "TestCombineToArrayAndSumEvent",
                                new CombinerAndReconstitutor(
                                        w ->
                                                new TestCombineToArrayAndSumEventCombiner(
                                                        Configuration.DEFAULT, w),
                                        "jdk.combined.TestCombineToArrayAndSum",
                                        new TestCombineArrayAndSumReconstitutor())),
                        () -> {
                            TestCombineToArrayAndSumEvent.create(1, 2, 3);
                            TestCombineToArrayAndSumEvent.create(1, 2, 4);
                            TestCombineToArrayAndSumEvent.create(1, 3, 3);
                            TestCombineToArrayAndSumEvent.create(1, 4, 3);
                            TestCombineToArrayAndSumEvent.create(2, 1, 1);
                        });
        assertEquals(5, res.recordedEvents.size());
        assertEquals(4, res.readEvents.size());
        assertEquals(2, res.combinedEventCount.get("jdk.combined.TestCombineToArrayAndSum"));

        assertEqualsTV(Map.of("state2", 2, "val", 7), res.readEvents.get(0));
        assertEqualsTV(Map.of("state2", 3, "val", 3), res.readEvents.get(1));
        assertEqualsTV(Map.of("state2", 4, "val", 3), res.readEvents.get(2));
        assertEqualsTV(Map.of("state2", 1, "val", 1), res.readEvents.get(3));
    }

    /**
     * Test {@link me.bechberger.jfr.JFREventCombiner.ObjectAllocationSampleCombiner} and {@link
     * ObjectAllocationSampleReconstitutor}
     */
    @Test
    public void testObjectAllocationSampleCombiner() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.ObjectAllocationSample",
                                new CombinerAndReconstitutor(
                                        "jdk.combined.ObjectAllocationSample")),
                        Configuration.DEFAULT.withCombineObjectAllocationSampleEvents(true),
                        () -> {
                            System.out.println(new byte[1024 * 1024 * 1024].length);
                            System.gc();
                        });
        assertTrue(
                res.combinedEventCount.size() <= res.readEvents.size(),
                "Less combined then recorded events");
        Map<String, Long> sizePerClass = new HashMap<>();
        for (var event : res.recordedEvents) {
            var className = event.getClass("objectClass").getName().replace('.', '/');
            var weight = event.getLong("weight");
            sizePerClass.put(className, sizePerClass.getOrDefault(className, 0L) + weight);
        }
        Map<String, Long> reconSizePerClass = new HashMap<>();
        for (var event : res.readEvents) {
            if (!event.getType().getTypeName().equals("jdk.ObjectAllocationSample")) {
                continue;
            }
            var objClass = (TypedValue) TypedValueUtil.getNonScalar(event, "objectClass");
            var className = TypedValueUtil.get(objClass, "name").toString();
            var weight = (long) TypedValueUtil.get(event, "weight");
            reconSizePerClass.put(
                    className, reconSizePerClass.getOrDefault(className, 0L) + weight);
        }
        assertMapEquals(sizePerClass, reconSizePerClass);
    }

    /**
     * Test {@link me.bechberger.jfr.JFREventCombiner.PromoteObjectCombiner} and {@link
     * PromoteObjectCombiner}
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPromoteObjectInNewTLABCombiner(boolean sumObjectSizes) {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.PromoteObjectInNewPLAB",
                                new CombinerAndReconstitutor(
                                        "jdk.combined.PromoteObjectInNewPLAB")),
                        Configuration.DEFAULT
                                .withCombinePLABPromotionEvents(true)
                                .withSumObjectSizes(sumObjectSizes),
                        () -> {
                            System.out.println(new byte[1024 * 1024 * 1024].length);
                            System.gc();
                        });
        // idea: sum sizes per tenuringAge per Object class
        Map<String, Map<Long, Long>> sizePerAgePerClass = new HashMap<>();
        for (var event : res.recordedEvents) {
            var className = event.getClass("objectClass").getName().replace('.', '/');
            var perAge = sizePerAgePerClass.computeIfAbsent(className, k -> new HashMap<>());
            var age = event.getLong("tenuringAge");
            perAge.put(age, perAge.getOrDefault(age, 0L) + event.getLong("objectSize"));
        }
        Map<String, Map<Long, Long>> reconSizePerAgePerClass = new HashMap<>();
        for (var event : res.readEvents) {
            var objClass = (TypedValue) TypedValueUtil.getNonScalar(event, "objectClass");
            var className = TypedValueUtil.get(objClass, "name").toString().replace('.', '/');
            var age = (long) (int) TypedValueUtil.get(event, "tenuringAge");
            var perAge = reconSizePerAgePerClass.computeIfAbsent(className, k -> new HashMap<>());
            perAge.put(
                    age,
                    perAge.getOrDefault(age, 0L) + TypedValueUtil.getLong(event, "objectSize"));
        }
        assertMapEquals(sizePerAgePerClass, reconSizePerAgePerClass);

        // Regression for the "lossless" preset silently dropping plabSize (every value became
        // -1 byte after inflate). plabSize is per-event data.
        Map<Long, Long> origPlabCounts = new HashMap<>();
        for (var event : res.recordedEvents) {
            origPlabCounts.merge(event.getLong("plabSize"), 1L, Long::sum);
        }
        Map<Long, Long> reconPlabCounts = new HashMap<>();
        for (var event : res.readEvents) {
            reconPlabCounts.merge(TypedValueUtil.getLong(event, "plabSize"), 1L, Long::sum);
        }
        assertFalse(
                reconPlabCounts.containsKey(-1L),
                "reconstituted PromoteObjectInNewPLAB must not carry the -1 plabSize sentinel");
        if (sumObjectSizes) {
            // Summing mode intentionally collapses to one event per (class, age, plabSize)
            // bucket, so only the SET of distinct plabSizes is preserved, not the counts.
            assertEquals(
                    origPlabCounts.keySet(),
                    reconPlabCounts.keySet(),
                    "the set of distinct plabSize values must survive summing combine");
        } else {
            // Lossless (array) mode — the DEFAULT/lossless preset — must preserve every event's
            // plabSize exactly.
            assertEquals(
                    origPlabCounts,
                    reconPlabCounts,
                    "plabSize value multiset must be preserved losslessly through"
                            + " combine/reconstitute");
        }
    }

    /**
     * Test {@link me.bechberger.jfr.JFREventCombiner.TenuringDistributionCombiner} and {@link
     * TenuringDistributionReconstitutor}
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testTenuringDistributionCombiner(boolean ignoreZeroSized) {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.TenuringDistribution",
                                new CombinerAndReconstitutor("jdk.combined.TenuringDistribution")),
                        Configuration.DEFAULT
                                .withCombineEventsWithoutDataLoss(true)
                                .withIgnoreZeroSizedTenuredAges(ignoreZeroSized),
                        () -> {
                            System.out.println(new byte[1024 * 1024 * 1024].length);
                            System.gc();
                        });
        assertTrue(
                res.combinedEventCount.size() <= res.readEvents.size(),
                "Less combined then recorded events");
        Map<Long, Long> sizePerAge = new HashMap<>();
        for (var event : res.recordedEvents) {
            var age = event.getLong("age");
            var size = event.getLong("size");
            sizePerAge.put(age, sizePerAge.getOrDefault(age, 0L) + size);
        }
        Map<Long, Long> reconSizePerAge = new HashMap<>();
        for (var event : res.readEvents) {
            var age = TypedValueUtil.getLong(event, "age");
            var size = TypedValueUtil.getLong(event, "size");
            reconSizePerAge.put(age, reconSizePerAge.getOrDefault(age, 0L) + size);
        }
        assertMapEquals(sizePerAge, reconSizePerAge, (age, size) -> size == 0 && ignoreZeroSized);
    }

    /**
     * Test {@link me.bechberger.jfr.JFREventCombiner.GCPhasePauseLevelCombiner} and {@link
     * GCPhasePauseLevelReconstitutor}
     */
    @Test
    public void testGCPhasePauseLevelCombiner() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.GCPhasePauseLevel1",
                                new CombinerAndReconstitutor("jdk.combined.GCPhasePauseLevel1")),
                        Configuration.DEFAULT
                                .withTimeStampTicksPerSecond(1_000_000_000)
                                .withCombineEventsWithoutDataLoss(true)
                                .withCombinePLABPromotionEvents(false)
                                .withIgnoreTooShortGCPauses(false),
                        () -> {
                            System.out.println(new byte[1024 * 1024 * 1024].length);
                            System.gc();
                        });
        assertTrue(
                res.combinedEventCount.size() <= res.readEvents.size(),
                "Less combined then recorded events");
        Map<String, Long> durationPerPhase = new HashMap<>();
        for (var event : res.recordedEvents) {
            var name = event.getString("name");
            var duration = event.getDuration("duration").toNanos();
            durationPerPhase.put(name, durationPerPhase.getOrDefault(name, 0L) + duration);
        }
        Map<String, Long> reconDurationPerPhase = new HashMap<>();
        for (var event : res.readEvents) {
            var name = (String) TypedValueUtil.get(event, "name");
            var duration = TypedValueUtil.getLong(event, "duration");
            reconDurationPerPhase.put(
                    name, reconDurationPerPhase.getOrDefault(name, 0L) + duration);
        }
        // Allow ±1ns tolerance per phase: the combiner stores ticks and rounding on
        // conversion can produce a 1-tick difference per combined event.
        for (var entry : durationPerPhase.entrySet()) {
            var recon = reconDurationPerPhase.get(entry.getKey());
            assertNotNull(recon, "Missing phase in reconstituted output: " + entry.getKey());
            assertTrue(
                    Math.abs(entry.getValue() - recon) <= 1,
                    "Duration mismatch for phase '"
                            + entry.getKey()
                            + "': expected "
                            + entry.getValue()
                            + ", got "
                            + recon);
        }
    }

    /**
     * Guard against a GCPhasePauseLevelN event-count drop (sibling of the GCPhaseParallel bug):
     * within a single GC id, parallel GC sub-phases can emit multiple events with the SAME phase
     * name (e.g. G1's "Balance queues"). The combiner stores per-(gcId, name) durations, and if it
     * uses a single value per name instead of an array, all but the last same-named phase are
     * dropped. This asserts the count is preserved 1:1 through combine-reconstitute.
     */
    @Test
    public void testGCPhasePauseLevelCountIsPreserved() {
        for (String type :
                List.of(
                        "jdk.GCPhasePauseLevel1",
                        "jdk.GCPhasePauseLevel2",
                        "jdk.GCPhasePauseLevel3",
                        "jdk.GCPhasePauseLevel4")) {
            var res =
                    runJFRWithCombiner(
                            Map.of(
                                    type,
                                    new CombinerAndReconstitutor(
                                            "jdk.combined." + type.substring("jdk.".length()))),
                            Configuration.DEFAULT
                                    .withCombineEventsWithoutDataLoss(true)
                                    .withCombinePLABPromotionEvents(false)
                                    .withIgnoreTooShortGCPauses(false),
                            () -> {
                                for (int i = 0; i < 10; i++) {
                                    System.out.println(new byte[64 * 1024 * 1024].length);
                                    System.gc();
                                }
                            });
            long recorded =
                    res.recordedEvents.stream()
                            .filter(e -> e.getEventType().getName().equals(type))
                            .count();
            long reconstituted =
                    res.readEvents.stream()
                            .filter(e -> e.getType().getTypeName().equals(type))
                            .count();
            if (recorded == 0) {
                // Not every GC produces every level; skip levels this run didn't emit.
                continue;
            }
            assertEquals(
                    recorded,
                    reconstituted,
                    type
                            + " count must be preserved through combine-reconstitute; a drop means"
                            + " same-named phases within a GC id were collapsed to one entry");
        }
    }

    /**
     * Guard against the GCPhaseParallel event-count drop observed in a real condense-inflate run
     * (colleague's recording: 50359 jdk.GCPhaseParallel events collapsed to 9026 after inflate).
     *
     * <p>Each jdk.GCPhaseParallel event records one worker's phase timing; the combiner stores them
     * as an array per (gcId, name) and the reconstitutor must flatMap every worker struct back out.
     * If the reconstitutor emits one event per (gcId, name) instead of one per worker, the count
     * collapses. This test asserts the count is preserved 1:1 through combine-reconstitute.
     */
    @Test
    public void testGCPhaseParallelCountIsPreserved() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.GCPhaseParallel",
                                new CombinerAndReconstitutor("jdk.combined.GCPhaseParallel")),
                        Configuration.DEFAULT
                                .withCombineEventsWithoutDataLoss(true)
                                .withCombinePLABPromotionEvents(false),
                        () -> {
                            for (int i = 0; i < 10; i++) {
                                System.out.println(new byte[64 * 1024 * 1024].length);
                                System.gc();
                            }
                        });
        long recorded =
                res.recordedEvents.stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.GCPhaseParallel"))
                        .count();
        long reconstituted =
                res.readEvents.stream()
                        .filter(e -> e.getType().getTypeName().equals("jdk.GCPhaseParallel"))
                        .count();
        assertTrue(recorded > 0, "Test should record some jdk.GCPhaseParallel events");
        assertEquals(
                recorded,
                reconstituted,
                "jdk.GCPhaseParallel count must be preserved through condense-inflate; "
                        + "a drop here reproduces the colleague's 50359 to 9026 regression");
    }

    /**
     * Guard against GCPhaseParallel worker durations being silently zeroed under configs with
     * reduced timestamp precision (e.g. reasonable-default: timeStampTicksPerSecond=1_000 = 1ms).
     *
     * <p>The duration field inside the GCWorker nested struct must use durationTicksPerSecond (1µs
     * for reasonable-default) not timeStampTicksPerSecond (1ms). When topLevel=true was passed to
     * eventFieldToField for the nested duration field, the 1ms precision caused every sub-ms worker
     * duration (typically 1–100µs) to be stored as zero. The view then displayed "0s" for all
     * GCPhaseParallel events.
     */
    @Test
    public void testGCPhaseParallelDurationsPreservedWithReducedTimestampPrecision() {
        // reasonable-default: timeStampTicksPerSecond=1_000 (1ms), durationTicksPerSecond=1_000_000
        // (1µs). Before the fix, the nested duration field used timestamp precision (1ms), zeroing
        // all sub-ms worker durations.
        var config =
                Configuration.REASONABLE_DEFAULT
                        .withCombineEventsWithoutDataLoss(true)
                        .withCombinePLABPromotionEvents(false);
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.GCPhaseParallel",
                                new CombinerAndReconstitutor("jdk.combined.GCPhaseParallel")),
                        config,
                        () -> {
                            for (int i = 0; i < 5; i++) {
                                System.out.println(new byte[64 * 1024 * 1024].length);
                                System.gc();
                            }
                        });
        long recorded =
                res.recordedEvents.stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.GCPhaseParallel"))
                        .count();
        assertTrue(recorded > 0, "Test should record some jdk.GCPhaseParallel events");

        // At least one reconstituted event must have a non-zero duration. Before the fix every
        // worker duration was zeroed because the nested struct used timestamp precision (1ms)
        // instead of duration precision (1µs), truncating all sub-ms GCPhaseParallel durations.
        long nonZeroDurations =
                res.readEvents.stream()
                        .filter(e -> e.getType().getTypeName().equals("jdk.GCPhaseParallel"))
                        .filter(e -> TypedValueUtil.getLong(e, "duration") != 0)
                        .count();
        assertTrue(
                nonZeroDurations > 0,
                "At least one reconstituted jdk.GCPhaseParallel event must have a non-zero duration"
                    + " under reasonable-default config (durationTicksPerSecond=1_000_000 gives 1µs"
                    + " precision, enough for typical 1–100µs worker phases). All-zero durations"
                    + " reproduce the bug where topLevel=true caused the nested duration field to"
                    + " use 1ms timestamp precision instead.");
    }

    /**
     * The GCPhaseParallel combiner groups per-worker timings under a per-gcId map keyed by the
     * phase name ("ObjCopy", "ScanHR", ...). There are only ~30 distinct names but they repeat
     * across every GC id, so writing each key inline re-serializes the same short UTF-8 strings
     * thousands of times. Measured on a real recording this was ~125 KB (44%) of the combined
     * event's bytes.
     *
     * <p>The name key must therefore be stored by reference (a small varint index into a per-type
     * string cache), so the total String bytes attributed to the combined GCPhaseParallel node stay
     * bounded by the distinct-name vocabulary rather than growing with the number of GC ids.
     */
    @Test
    public void testGCPhaseParallelNameKeyIsDeduplicated() {
        EventWriteTree[] rootHolder = new EventWriteTree[1];
        var res =
                runJFRWithCombinerCapturingStats(
                        Map.of(
                                "jdk.GCPhaseParallel",
                                new CombinerAndReconstitutor("jdk.combined.GCPhaseParallel")),
                        Configuration.DEFAULT
                                .withCombineEventsWithoutDataLoss(true)
                                .withCombinePLABPromotionEvents(false),
                        () -> {
                            for (int i = 0; i < 10; i++) {
                                System.out.println(new byte[64 * 1024 * 1024].length);
                                System.gc();
                            }
                        },
                        rootHolder);

        long recorded =
                res.recordedEvents.stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.GCPhaseParallel"))
                        .count();
        assertTrue(recorded > 100, "Test should record many jdk.GCPhaseParallel events");

        // Locate the combined GCPhaseParallel node and sum the String bytes beneath it.
        EventWriteTree combined = findNode(rootHolder[0], "jdk.combined.GCPhaseParallel");
        assertNotNull(
                combined, "Should have a jdk.combined.GCPhaseParallel node in the write tree");
        long stringBytes = sumBytesForCause(combined, "String");

        // With dedup the phase names cost a tiny fixed vocabulary (a few hundred bytes even with
        // ~30 names). Inline would cost ~8+ bytes per (gcId, name) pair, i.e. thousands of bytes.
        // Assert the String bytes are far below what inlining every recorded event would produce.
        assertTrue(
                stringBytes < recorded,
                "Phase-name String bytes ("
                        + stringBytes
                        + ") must be far below the recorded-event count ("
                        + recorded
                        + "); a value near or above it means names are written inline per gcId "
                        + "instead of by reference.");
    }

    /**
     * The per-worker eventThread for GCPhaseParallel is kept in the default and reasonable-default
     * presets (a colleague relies on it) and only dropped in reduced-default. We detect the field's
     * presence/absence via the write-statistics tree: zero Thread bytes under the combined
     * GCPhaseParallel node means the thread field was omitted.
     */
    @Test
    public void testGCPhaseParallelWorkerThreadDroppedOnlyInReducedDefault() {
        Runnable gc =
                () -> {
                    for (int i = 0; i < 10; i++) {
                        System.out.println(new byte[64 * 1024 * 1024].length);
                        System.gc();
                    }
                };
        var combiners =
                Map.of(
                        "jdk.GCPhaseParallel",
                        new CombinerAndReconstitutor("jdk.combined.GCPhaseParallel"));

        // Presets that must KEEP the per-worker thread.
        for (var config :
                List.of(
                        Configuration.DEFAULT
                                .withCombineEventsWithoutDataLoss(true)
                                .withCombinePLABPromotionEvents(false),
                        Configuration.REASONABLE_DEFAULT
                                .withCombineEventsWithoutDataLoss(true)
                                .withCombinePLABPromotionEvents(false))) {
            EventWriteTree[] rootHolder = new EventWriteTree[1];
            runJFRWithCombinerCapturingStats(combiners, config, gc, rootHolder);
            EventWriteTree combined = findNode(rootHolder[0], "jdk.combined.GCPhaseParallel");
            assertNotNull(combined, config.name() + ": combined node present");
            assertTrue(
                    sumBytesForCause(combined, "java.lang.Thread") > 0,
                    config.name() + ": per-worker eventThread must be kept");
        }

        // reduced-default still drops it to save space.
        EventWriteTree[] rootHolder = new EventWriteTree[1];
        runJFRWithCombinerCapturingStats(combiners, Configuration.REDUCED_DEFAULT, gc, rootHolder);
        EventWriteTree combined = findNode(rootHolder[0], "jdk.combined.GCPhaseParallel");
        assertNotNull(combined, "reduced-default: combined node present");
        assertEquals(
                0,
                sumBytesForCause(combined, "java.lang.Thread"),
                "reduced-default: per-worker eventThread must be dropped");
    }

    /** Depth-first search for the first node whose cause name equals {@code causeName}. */
    private static @Nullable EventWriteTree findNode(EventWriteTree node, String causeName) {
        if (node == null) {
            return null;
        }
        if (causeName.equals(node.getCauseName())) {
            return node;
        }
        for (var child : node.getChildren()) {
            var found = findNode(child, causeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Sum direct bytes of every descendant (and self) whose cause name equals {@code causeName}.
     */
    private static long sumBytesForCause(EventWriteTree node, String causeName) {
        long total = 0;
        if (causeName.equals(node.getCauseName())) {
            total += node.getDirectBytesWritten();
        }
        for (var child : node.getChildren()) {
            total += sumBytesForCause(child, causeName);
        }
        return total;
    }

    private static <T, V> void assertMapEquals(Map<T, V> expected, Map<T, V> actual) {
        assertMapEquals(expected, actual, (k, v) -> false);
    }

    private static <T, V> void assertMapEquals(
            Map<T, V> expected, Map<T, V> actual, BiPredicate<T, V> canKeyBeNonPresent) {
        Set<T> missingKeys = new HashSet<>();
        Set<T> mismatchedKeys = new HashSet<>();
        Set<T> extraKeys = new HashSet<>();

        for (var key : expected.keySet()) {
            if (!actual.containsKey(key)) {
                if (!canKeyBeNonPresent.test(key, expected.get(key))) {
                    missingKeys.add(key);
                }
                continue;
            }
            if (expected.get(key).equals(0L)) {
                if (actual.get(key) != null && !actual.get(key).equals(0L)) {
                    mismatchedKeys.add(key);
                }
                continue;
            }
            if (!expected.get(key).equals(actual.get(key))) {
                mismatchedKeys.add(key);
            }
        }

        // Check for extra keys in actual that aren't in expected
        for (var key : actual.keySet()) {
            if (!expected.containsKey(key)) {
                extraKeys.add(key);
            }
        }

        if (!missingKeys.isEmpty() || !mismatchedKeys.isEmpty() || !extraKeys.isEmpty()) {
            StringBuilder error = new StringBuilder("Map comparison failed:\n");
            error.append("Expected size: ")
                    .append(expected.size())
                    .append(", Actual size: ")
                    .append(actual.size())
                    .append("\n");
            if (!missingKeys.isEmpty()) {
                error.append("Missing keys in actual (")
                        .append(missingKeys.size())
                        .append("): ")
                        .append(missingKeys)
                        .append("\n");
                for (T key : missingKeys) {
                    error.append("  ")
                            .append(key)
                            .append(": expected=")
                            .append(expected.get(key))
                            .append("\n");
                }
            }
            if (!mismatchedKeys.isEmpty()) {
                error.append("Mismatched values (")
                        .append(mismatchedKeys.size())
                        .append("): ")
                        .append(mismatchedKeys)
                        .append("\n");
                for (T key : mismatchedKeys) {
                    error.append("  ")
                            .append(key)
                            .append(": expected=")
                            .append(expected.get(key))
                            .append(", actual=")
                            .append(actual.get(key))
                            .append("\n");
                }
            }
            if (!extraKeys.isEmpty()) {
                error.append("Extra keys in actual (")
                        .append(extraKeys.size())
                        .append("): ")
                        .append(extraKeys)
                        .append("\n");
                for (T key : extraKeys) {
                    error.append("  ")
                            .append(key)
                            .append(": actual=")
                            .append(actual.get(key))
                            .append("\n");
                }
            }
            fail(error.toString());
        }
    }

    private static void assertEqualsTV(Map<String, Object> expected, TypedValue event) {
        for (var entry : expected.entrySet()) {
            assertEquals(
                    entry.getValue(),
                    TypedValueUtil.get(event, entry.getKey()),
                    "Wrong value for field " + entry.getKey());
        }
    }

    record EventCombinerTestResult(
            List<RecordedEvent> recordedEvents,
            List<TypedValue> readEvents,
            Map<String, Integer> combinedEventCount) {}

    record CombinerAndReconstitutor(
            @Nullable Function<BasicJFRWriter, ? extends Combiner<?, ?>> combiner,
            String combinedEvent,
            @Nullable AbstractReconstitutor<?> reconstitutor) {
        CombinerAndReconstitutor(String combinedEvent) {
            this(null, combinedEvent, null);
        }
    }

    /**
     * Test {@link me.bechberger.jfr.JFREventCombiner.BasicObjectAllocationCombiner} and {@link
     * BasicObjectAllocationReconstitutor} for jdk.ObjectAllocationInNewTLAB
     */
    @Test
    public void testObjectAllocationInNewTLABCombiner() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.ObjectAllocationInNewTLAB",
                                new CombinerAndReconstitutor(
                                        "jdk.combined.ObjectAllocationInNewTLAB")),
                        Configuration.DEFAULT.withCombineObjectAllocationSampleEvents(true),
                        () -> {
                            for (int i = 0; i < 20; i++) {
                                System.out.println(new byte[16 * 1024].length);
                            }
                        });
        assertTrue(
                res.combinedEventCount.size() <= res.readEvents.size(),
                "Less combined than recorded events");
        Map<String, Long> sizePerClass = new HashMap<>();
        for (var event : res.recordedEvents) {
            var className = event.getClass("objectClass").getName().replace('.', '/');
            var size = event.getLong("allocationSize");
            sizePerClass.put(className, sizePerClass.getOrDefault(className, 0L) + size);
        }
        Map<String, Long> reconSizePerClass = new HashMap<>();
        for (var event : res.readEvents) {
            if (!event.getType().getTypeName().equals("jdk.ObjectAllocationInNewTLAB")) {
                continue;
            }
            var objClass = (TypedValue) TypedValueUtil.getNonScalar(event, "objectClass");
            var className = TypedValueUtil.get(objClass, "name").toString();
            var size = TypedValueUtil.getLong(event, "allocationSize");
            reconSizePerClass.put(className, reconSizePerClass.getOrDefault(className, 0L) + size);
        }
        assertMapEquals(sizePerClass, reconSizePerClass);
    }

    /**
     * Test {@link me.bechberger.jfr.JFREventCombiner.BasicObjectAllocationCombiner} and {@link
     * BasicObjectAllocationReconstitutor} for jdk.ObjectAllocationOutsideTLAB
     */
    @Test
    public void testObjectAllocationOutsideTLABCombiner() {
        var res =
                runJFRWithCombiner(
                        Map.of(
                                "jdk.ObjectAllocationOutsideTLAB",
                                new CombinerAndReconstitutor(
                                        "jdk.combined.ObjectAllocationOutsideTLAB")),
                        Configuration.DEFAULT.withCombineObjectAllocationSampleEvents(true),
                        () -> {
                            for (int i = 0; i < 10; i++) {
                                System.out.println(new byte[8 * 1024 * 1024].length);
                            }
                        });
        assertTrue(
                res.combinedEventCount.size() <= res.readEvents.size(),
                "Less combined than recorded events");
        Map<String, Long> sizePerClass = new HashMap<>();
        for (var event : res.recordedEvents) {
            var className = event.getClass("objectClass").getName().replace('.', '/');
            var size = event.getLong("allocationSize");
            sizePerClass.put(className, sizePerClass.getOrDefault(className, 0L) + size);
        }
        Map<String, Long> reconSizePerClass = new HashMap<>();
        for (var event : res.readEvents) {
            if (!event.getType().getTypeName().equals("jdk.ObjectAllocationOutsideTLAB")) {
                continue;
            }
            var objClass = (TypedValue) TypedValueUtil.getNonScalar(event, "objectClass");
            var className = TypedValueUtil.get(objClass, "name").toString();
            var size = TypedValueUtil.getLong(event, "allocationSize");
            reconSizePerClass.put(className, reconSizePerClass.getOrDefault(className, 0L) + size);
        }
        assertMapEquals(sizePerClass, reconSizePerClass);
    }

    EventCombinerTestResult runJFRWithCombiner(
            Map<String, CombinerAndReconstitutor> combiners, Runnable createEvents) {
        return runJFRWithCombiner(combiners, Configuration.DEFAULT, createEvents);
    }

    EventCombinerTestResult runJFRWithCombiner(
            Map<String, CombinerAndReconstitutor> combiners,
            Configuration configuration,
            Runnable createEvents) {
        List<RecordedEvent> recordedEvents = new ArrayList<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out, configuration);
            try (RecordingStream rs = new RecordingStream()) {
                for (var combiner : combiners.entrySet()) {
                    rs.enable(combiner.getKey());
                    rs.onEvent(
                            combiner.getKey(),
                            event -> {
                                if (combiner.getValue().combiner != null) {
                                    var combinerInstance =
                                            combiner.getValue().combiner.apply(basicJFRWriter);
                                    basicJFRWriter
                                            .getEventCombiner()
                                            .putIfNotThere(
                                                    event.getEventType(), () -> combinerInstance);
                                }
                                basicJFRWriter.processEvent(event);
                                recordedEvents.add(event);
                            });
                }
                rs.onEvent(
                        "EventThatEndsRecording",
                        event -> {
                            System.out.println("EventThatEndsRecording");
                            rs.close();
                        });
                rs.startAsync();
                createEvents.run();
                EventThatEndsRecording.create();
                System.out.println("Waiting for recording to end");
                rs.awaitTermination();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            basicJFRWriter.close();
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            WritingJFRReader reader =
                    new WritingJFRReader(
                            new BasicJFRReader(
                                    in, BasicJFRReader.Options.DEFAULT.withReconstitute(false)));
            for (var combiner : combiners.values()) {
                if (combiner.reconstitutor != null) {
                    reader.getReconstitutor()
                            .put(
                                    combiner.combinedEvent,
                                    JFREventTypedValueCombiner.createTypedValueReconstitutor(
                                            combiner.reconstitutor, reader));
                }
            }
            var readEvents = reader.readAllJFREvents();
            return new EventCombinerTestResult(
                    recordedEvents, readEvents, reader.getCombinedEventCount());
        }
    }

    /**
     * Variant of {@link #runJFRWithCombiner} that enables full write statistics and, after
     * condensing, stores the statistics context-root tree into {@code rootHolder[0]} so a test can
     * attribute bytes per write cause. Reconstitution/read-back is skipped because these tests only
     * assert on the write side.
     */
    EventCombinerTestResult runJFRWithCombinerCapturingStats(
            Map<String, CombinerAndReconstitutor> combiners,
            Configuration configuration,
            Runnable createEvents,
            EventWriteTree[] rootHolder) {
        List<RecordedEvent> recordedEvents = new ArrayList<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            out.enableFullStatistics();
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out, configuration);
            try (RecordingStream rs = new RecordingStream()) {
                for (var combiner : combiners.entrySet()) {
                    rs.enable(combiner.getKey());
                    rs.onEvent(
                            combiner.getKey(),
                            event -> {
                                if (combiner.getValue().combiner != null) {
                                    var combinerInstance =
                                            combiner.getValue().combiner.apply(basicJFRWriter);
                                    basicJFRWriter
                                            .getEventCombiner()
                                            .putIfNotThere(
                                                    event.getEventType(), () -> combinerInstance);
                                }
                                basicJFRWriter.processEvent(event);
                                recordedEvents.add(event);
                            });
                }
                rs.onEvent(
                        "EventThatEndsRecording",
                        event -> {
                            System.out.println("EventThatEndsRecording");
                            rs.close();
                        });
                rs.startAsync();
                createEvents.run();
                EventThatEndsRecording.create();
                System.out.println("Waiting for recording to end");
                rs.awaitTermination();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            basicJFRWriter.close();
            rootHolder[0] = out.getStatistics().getContextRoot();
        }
        return new EventCombinerTestResult(recordedEvents, List.of(), Map.of());
    }
}
