package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        public TestCombineArrayReconstitutor(WritingJFRReader jfrWriter) {
            super("TestCombineToArrayEvent", jfrWriter);
        }

        @Override
        public List<TypedValue> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                TypedValueEventBuilder builder) {
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
                                        TestCombineArrayReconstitutor::new)),
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
            assertEquals(recordedEvent.getInt("state"), get(readEvent, "state"));
            assertEquals(recordedEvent.getInt("val"), get(readEvent, "val"));
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

        public TestCombineArrayAndSumReconstitutor(WritingJFRReader jfrWriter) {
            super("TestCombineToArrayAndSumEvent", jfrWriter);
        }

        @Override
        public List<TypedValue> reconstitute(
                StructType<?, ?> resultEventType,
                ReadStruct combinedReadEvent,
                TypedValueEventBuilder builder) {
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
                                        TestCombineArrayAndSumReconstitutor::new)),
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
            var objClass = (TypedValue) get2(event, "objectClass");
            var className = get(objClass, "name").toString();
            var weight = (long) get(event, "weight");
            reconSizePerClass.put(
                    className, reconSizePerClass.getOrDefault(className, 0L) + weight);
        }
        assertMapEquals(sizePerClass, reconSizePerClass);
    }

    private static <T, V> void assertMapEquals(Map<T, V> expected, Map<T, V> actual) {
        for (var expectedClass : expected.keySet()) {
            if (actual.get(expectedClass) == null) {
                fail(expectedClass + " not found");
            }
            assertEquals(expected.get(expectedClass), actual.get(expectedClass));
        }
    }

    private static void assertEqualsTV(Map<String, Object> expected, TypedValue event) {
        for (var entry : expected.entrySet()) {
            assertEquals(
                    entry.getValue(),
                    get(event, entry.getKey()),
                    "Wrong value for field " + entry.getKey());
        }
    }

    private static Object get(TypedValue value, String name) {
        return value.getFieldValues().stream()
                .filter(f -> f.getField().getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue()
                .getValue();
    }

    private static Object get2(TypedValue value, String name) {
        return value.getFieldValues().stream()
                .filter(f -> f.getField().getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    record EventCombinerTestResult(
            List<RecordedEvent> recordedEvents,
            List<TypedValue> readEvents,
            Map<String, Integer> combinedEventCount) {}

    record CombinerAndReconstitutor(
            @Nullable Function<BasicJFRWriter, ? extends Combiner<?, ?>> combiner,
            String combinedEvent,
            @Nullable Function<WritingJFRReader, AbstractReconstitutor<?>> reconstitutor) {
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
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out, Configuration.REDUCED_DEFAULT);
            try (RecordingStream rs = new RecordingStream()) {
                for (var combiner : combiners.entrySet()) {
                    System.out.println("enable " + combiner.getKey());
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
            WritingJFRReader reader = new WritingJFRReader(new BasicJFRReader(in));
            for (var combiner : combiners.values()) {
                if (combiner.reconstitutor != null) {
                    reader.getReconstitutor()
                            .put(combiner.combinedEvent, combiner.reconstitutor.apply(reader));
                }
            }
            var readEvents = reader.readAllJFREvents();
            return new EventCombinerTestResult(
                    recordedEvents, readEvents, reader.getCombinedEventCount());
        }
    }
}
