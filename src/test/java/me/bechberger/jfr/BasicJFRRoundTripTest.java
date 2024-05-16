package me.bechberger.jfr;

import static me.bechberger.condensed.Util.equalUnderBf16Conversion;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.Util;
import me.bechberger.util.Asserters;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class BasicJFRRoundTripTest {

    @Name("TestEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace()
    static class TestEvent extends Event {
        @Label("Label")
        int number;

        @Label("Memory")
        @DataAmount
        long memory = Runtime.getRuntime().freeMemory();

        @Label("String")
        String string = "Hello" + memory;

        TestEvent(int number) {
            this.number = number;
        }
    }

    @Test
    public void testBasicTestEventRoundTrip() throws InterruptedException {
        AtomicReference<RecordedEvent> recordedEvent = new AtomicReference<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final int ttps = 100;
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter =
                    new BasicJFRWriter(
                            out, Configuration.DEFAULT.withTimeStampTicksPerSecond(ttps));
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            recordedEvent.set(event);
                            rs.close();
                        });
                rs.startAsync();
                TestEvent testEvent = new TestEvent(0);
                testEvent.commit();
                rs.awaitTermination();
            }
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            BasicJFRReader basicJFRReader = new BasicJFRReader(in);
            var readEvent = basicJFRReader.readNextEvent();
            assertNotNull(readEvent);
            assertEquals("TestEvent", readEvent.getType().getName());
            assertEquals("[\"Label\",\"Description\"]", readEvent.getType().getDescription());
            assertEquals(ttps, basicJFRReader.getConfiguration().timeStampTicksPerSecond());
            assertEquals(
                    recordedEvent.get().getStartTime().getEpochSecond(),
                    readEvent.get(Instant.class, "startTime").getEpochSecond());
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            var bos = new ByteArrayOutputStream();
            var jfrReader = new WritingJFRReader(new BasicJFRReader(in), bos);
            var readEvent = jfrReader.readNextJFREvent();
            assertNotNull(readEvent);
            assertEquals("TestEvent", readEvent.getType().getTypeName());
        }
    }

    /**
     * Write {@link TestEvent} twice and read it back with the built-in JFR reader, comparing the
     * results.
     *
     * <p>Important: Stacktrace equality is only minus recursive class objects, as the {@link
     * WritingJFRReader} can't handle self-recursive objects (yet).
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 11, 1001, 1_000_000_000})
    public void testTestEventRoundTrip(int ticksPerSecond) throws InterruptedException {
        List<RecordedEvent> recordedEvents = new ArrayList<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        var memoryAsFloatB16 = ticksPerSecond == 1;
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter =
                    new BasicJFRWriter(
                            out,
                            Configuration.DEFAULT
                                    .withTimeStampTicksPerSecond(ticksPerSecond)
                                    .withDurationTicksPerSecond(ticksPerSecond)
                                    .withMemoryAsBFloat16(memoryAsFloatB16));
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            recordedEvents.add(event);
                            if (recordedEvents.size() == 2) rs.close();
                        });

                rs.startAsync();
                new TestEvent(0).commit();
                Thread.sleep(10);
                var evt = new TestEvent(1);
                evt.begin();
                Thread.sleep(5);
                evt.commit();
                rs.awaitTermination();
            }
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            var events = WritingJFRReader.toJFREventsList(new BasicJFRReader(in));
            assertEquals(2, recordedEvents.size());
            assertEquals(recordedEvents.size(), events.size());
            int number = 0;
            for (var pair : Util.zip(recordedEvents, events)) {
                var recordedEvent = pair.left;
                var event = pair.right;
                System.out.println(event);

                // Check type and number
                assertEquals("TestEvent", event.getEventType().getName());
                assertEquals(number, event.getInt("number"));

                // Check start time and duration
                Asserters.assertEquals(
                        recordedEvent.getStartTime(), event.getStartTime(), ticksPerSecond);
                Asserters.assertEquals(
                        recordedEvent.getDuration(), event.getDuration(), ticksPerSecond);

                // Check memory field
                if (memoryAsFloatB16) {
                    assertTrue(
                            equalUnderBf16Conversion(
                                    recordedEvent.getLong("memory"), event.getLong("memory")));
                } else {
                    assertEquals(recordedEvent.getLong("memory"), event.getLong("memory"));
                }

                // Check string field
                assertEquals(recordedEvent.getString("string"), event.getString("string"));

                // Check stack trace
                var recordedStackTrace = recordedEvent.getStackTrace();
                var stackTrace = event.getStackTrace();
                assertEquals(recordedStackTrace.isTruncated(), stackTrace.isTruncated());
                assertEquals(recordedStackTrace.getFrames().size(), stackTrace.getFrames().size());
                for (int i = 0; i < recordedStackTrace.getFrames().size(); i++) {
                    var recordedFrame = recordedStackTrace.getFrames().get(i);
                    var frame = stackTrace.getFrames().get(i);
                    Asserters.assertEquals(recordedFrame, frame, "frame " + i);
                }
                number++;
            }
        }
    }

    private void func1(int id) {
        func2(id);
    }

    private void func2(int id) {
        func3(id);
    }

    private void func3(int id) {
        if (id % 2 == 0) {
            func4(id);
        } else {
            func5(id);
        }
    }

    private void func4(int id) {
        new TestEvent(id).commit();
    }

    private void func5(int id) {
        new TestEvent(id).commit();
    }

    /**
     * Write multiple {@link TestEvent}s with different stack traces and read them back with the
     * built-in JFR reader, comparing the results.
     *
     * <p>Used to find a nasty bug related to recursive data types
     *
     * @param maxDepth maximum stack depth
     * @throws InterruptedException
     */
    @ParameterizedTest
    @CsvSource({
        "1,true",
        "11,true",
        "64,true",
        "-1,true",
        "1,false",
        "11,false",
        "64,false",
        "-1,false"
    })
    public void testTestEventWithStackTraceReduction(int maxDepth, boolean useSpecHashes)
            throws InterruptedException {
        extracted(maxDepth, useSpecHashes, Compression.DEFAULT);
    }

    @Property
    public void testTestEventWithAllCompressions(@ForAll Compression compression)
            throws InterruptedException {
        extracted(-1, true, compression);
    }

    private void extracted(int maxDepth, boolean useSpecHashes, Compression compression)
            throws InterruptedException {
        int count = 4;
        List<RecordedEvent> recordedEvents = new ArrayList<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter =
                    new BasicJFRWriter(
                            out,
                            Configuration.DEFAULT
                                    .withMaxStackTraceDepth(maxDepth)
                                    .withUseSpecificHashesAndRefs(useSpecHashes));
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            recordedEvents.add(event);
                            if (recordedEvents.size() == 2) rs.close();
                        });

                rs.startAsync();
                for (int i = 0; i < count; i++) {
                    func1(i);
                }
                rs.awaitTermination();
            }
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            var events = WritingJFRReader.toJFREventsList(new BasicJFRReader(in));
            assertEquals(count, recordedEvents.size());
            assertEquals(recordedEvents.size(), events.size());
            maxDepth = maxDepth == -1 ? Integer.MAX_VALUE : maxDepth;
            int number = 0;
            for (var pair : Util.zip(recordedEvents, events)) {
                var recordedEvent = pair.left;
                var event = pair.right;
                System.out.println(event);

                // Check type and number
                assertEquals("TestEvent", event.getEventType().getName());
                assertEquals(number, event.getInt("number"));

                // Check stack trace
                var recordedStackTrace = recordedEvent.getStackTrace();
                var stackTrace = event.getStackTrace();
                if (recordedStackTrace.getFrames().size() > maxDepth) {
                    assertTrue(stackTrace.isTruncated());
                    assertEquals(maxDepth, stackTrace.getFrames().size());
                } else {
                    assertEquals(recordedStackTrace.isTruncated(), stackTrace.isTruncated());
                    assertEquals(
                            recordedStackTrace.getFrames().size(), stackTrace.getFrames().size());
                }
                for (int i = 0;
                        i < Math.min(recordedStackTrace.getFrames().size(), maxDepth);
                        i++) {
                    var recordedFrame = recordedStackTrace.getFrames().get(i);
                    var frame = stackTrace.getFrames().get(i);
                    Asserters.assertEquals(recordedFrame, frame, "frame " + i);
                }
                number++;
            }
        }
    }
}
