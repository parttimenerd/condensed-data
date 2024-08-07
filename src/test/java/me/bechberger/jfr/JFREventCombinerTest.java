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
                res.combinedEventCount.size() < res.readEvents.size(),
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
        // assertMapEquals(sizePerClass, reconSizePerClass); // TODO
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
    // @Test // TODO
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
        assertMapEquals(durationPerPhase, reconDurationPerPhase, (age, size) -> false);
    }

    private static <T, V> void assertMapEquals(Map<T, V> expected, Map<T, V> actual) {
        assertMapEquals(expected, actual, (k, v) -> false);
    }

    private static <T, V> void assertMapEquals(
            Map<T, V> expected, Map<T, V> actual, BiPredicate<T, V> canKeyBeNonPresent) {
        for (var key : expected.keySet()) {
            if (actual.get(key) == null && !canKeyBeNonPresent.test(key, expected.get(key))) {
                fail(key + " not found");
            }
            if (expected.get(key).equals(0L)) {
                assertTrue(actual.get(key) == null || actual.get(key).equals(0L));
                continue;
            }
            assertEquals(expected.get(key), actual.get(key));
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
            WritingJFRReader reader = new WritingJFRReader(new BasicJFRReader(in, false));
            for (var combiner : combiners.values()) {
                if (combiner.reconstitutor != null) {
                    reader.getReconstitutor()
                            .put(
                                    combiner.combinedEvent,
                                    combiner.reconstitutor.createTypedValueReconstitutor(reader));
                }
            }
            var readEvents = reader.readAllJFREvents();
            return new EventCombinerTestResult(
                    recordedEvents, readEvents, reader.getCombinedEventCount());
        }
    }
}
