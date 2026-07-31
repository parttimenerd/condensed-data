package me.bechberger.jfr;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.*;
import jdk.jfr.Configuration;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;
import org.junit.jupiter.api.Test;

public class BasicJFRWriterTest {

    @Name("TestEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace()
    static class TestEvent extends Event {}

    /** Test writing an instance of {@link TestEvent} */
    @Test
    public void testTestEvent() throws Exception {
        AtomicReference<RecordedEvent> recordedEvent = new AtomicReference<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out);
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            recordedEvent.set(event);
                            rs.close();
                        });
                rs.startAsync();
                TestEvent testEvent = new TestEvent();
                testEvent.commit();
                rs.awaitTermination();
            }
            System.out.println(out.getStatistics().toPrettyString());
            var types = out.getTypeCollection();
            // ensure that there is a jdk.types.StackFrame type
            assertNotNull(types.getTypeOrNull("jdk.types.StackFrame"), "StackFrame type not found");
            // check that the type for TestEvent looks properly
            var teType = types.getTypeOrNull("TestEvent");
            assertNotNull(teType, "TestEvent type not found");
            assertEquals("TestEvent", teType.getName());
            assertEquals(
                    "[\"Label\",\"Description\",[[\"jdk.jfr.Name\",[\"TestEvent\"]],[\"jdk.jfr.Label\",[\"Label\"]],[\"jdk.jfr.Description\",[\"Description\"]]]]",
                    teType.getDescription());
            assertInstanceOf(StructType.class, teType, "TestEvent is not a struct type");
            // check that the type for TestEvent has a field named "stackTrace"
            var stackTraceField = ((StructType<?, ?>) teType).getField("stackTrace");
            assertNotNull(stackTraceField, "stackTrace field not found");
        }
        byte[] data = outputStream.toByteArray();
        try (var in = new CondensedInputStream(data)) {
            var instance = in.readNextInstance();
            assertNotNull(instance);
            var instanceValue = (ReadStruct) instance.value();
            System.out.println(instanceValue.toPrettyString(3));
        }
    }

    /** Test writing more JFR events */
    @Test
    public void testMultipleEvents() throws Exception {
        var outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(
                        outputStream, StartMessage.DEFAULT.compress(Compression.DEFAULT))) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out);
            try (RecordingStream rs =
                    new RecordingStream(Configuration.getConfiguration("default"))) {
                rs.onEvent(basicJFRWriter::processEvent);
                rs.startAsync();
                Thread.sleep(100);
                TestEvent testEvent = new TestEvent();
                testEvent.commit();
                Thread.sleep(100);
            }
            System.out.println(out.getStatistics().toPrettyString());
        }

        byte[] data = outputStream.toByteArray();
        System.out.println("Data length: " + data.length);
        try (var in = new CondensedInputStream(data)) {
            while (true) {
                var instance = in.readNextInstance();
                if (instance == null) {
                    break;
                }
                System.out.println(instance.type());
            }
        }
    }

    @Name("TestEvent2")
    @Label("Label")
    @Description("Description")
    static class TestEvent2 extends Event {
        int key;
        int value;

        TestEvent2(int key, int value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Guard against sub-millisecond event durations being silently zeroed under DEFAULT config
     * (timeStampTicksPerSecond=1_000 = 1ms, durationTicksPerSecond=1_000_000 = 1µs).
     *
     * <p>Before the fix, {@code getTimespanType} and {@code getDurationType} both used {@code
     * timeStampTicksPerSecond} (1ms) for the built-in {@code duration} field, quantizing any sub-ms
     * event duration to 0. The fix uses {@code durationTicksPerSecond} (1µs) for all duration
     * fields.
     */
    @Test
    public void testSubMillisecondTopLevelDurationPreservedUnderDefaultConfig() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<RecordedEvent> captured = new ArrayList<>();

        try (CondensedOutputStream out = new CondensedOutputStream(baos, StartMessage.DEFAULT)) {
            BasicJFRWriter writer =
                    new BasicJFRWriter(out, me.bechberger.jfr.Configuration.DEFAULT);
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("TestEvent").withThreshold(java.time.Duration.ZERO);
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            writer.processEvent(event);
                            captured.add(event);
                            if (captured.size() >= 5) rs.close();
                        });
                rs.startAsync();
                for (int i = 0; i < 5; i++) {
                    TestEvent e = new TestEvent();
                    e.begin();
                    Thread.sleep(0, 50_000); // 50µs
                    e.commit();
                }
                rs.awaitTermination();
            }
            writer.close();
        }

        assertTrue(captured.size() > 0, "Should have captured some events");
        long nonZeroInJfr = captured.stream().filter(e -> !e.getDuration().isZero()).count();
        assertTrue(nonZeroInJfr > 0, "Some recorded events should have non-zero duration");

        byte[] data = baos.toByteArray();
        BasicJFRReader reader = new BasicJFRReader(new CondensedInputStream(data));
        List<me.bechberger.condensed.ReadStruct> events = reader.readAll();
        long nonZeroInCjfr =
                events.stream()
                        .filter(s -> s.getType().getName().equals("TestEvent"))
                        .filter(
                                s -> {
                                    Object d = s.get("duration");
                                    return d instanceof Duration dur && !dur.isZero();
                                })
                        .count();
        assertTrue(
                nonZeroInCjfr > 0,
                "At least one reconstituted TestEvent must have a non-zero duration under DEFAULT"
                    + " config (durationTicksPerSecond=1_000_000 gives 1µs precision). All-zero"
                    + " durations would reproduce Bug 319 where the built-in duration field used"
                    + " 1ms timestamp precision instead of 1µs duration precision.");
    }

    @Test
    public void testEventDeduplication() throws InterruptedException {
        var outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(
                        outputStream, StartMessage.DEFAULT.compress(Compression.DEFAULT))) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out);
            basicJFRWriter.getDeduplication().put("TestEvent2", e -> e.getInt("key"), "value");
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent2",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            if (event.getInt("key") == -1) {
                                rs.close();
                            }
                        });
                rs.onEvent("TestEvent", basicJFRWriter::processEvent);
                rs.startAsync();
                new TestEvent2(1, 2).commit();
                new TestEvent2(1, 2).commit();
                new TestEvent2(1, 3).commit();
                new TestEvent2(1, 3).commit();
                new TestEvent2(1, 2).commit();
                new TestEvent2(2, 2).commit();
                new TestEvent().commit(); // commit before stopper so it's not lost on stream close
                new TestEvent2(-1, 2).commit(); // stop
                rs.awaitTermination();
            }
            basicJFRWriter.close();
        }
        byte[] data = outputStream.toByteArray();
        System.out.println("Data length: " + data.length);
        BasicJFRReader reader = new BasicJFRReader(new CondensedInputStream(data));
        var events = reader.readAll();
        assertEquals(6, events.size());
        assertEquals(
                List.of(entry(1, 2), entry(1, 3), entry(1, 2), entry(2, 2), entry(-1, 2)),
                events.stream()
                        .filter(s -> s.getType().getName().equals("TestEvent2"))
                        .map(s -> entry((int) (long) s.get("key"), (int) (long) s.get("value")))
                        .toList());
        assertEquals(
                1, events.stream().filter(s -> s.getType().getName().equals("TestEvent")).count());
    }
}
