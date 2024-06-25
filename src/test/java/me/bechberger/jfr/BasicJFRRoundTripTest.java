package me.bechberger.jfr;

import static me.bechberger.condensed.Util.equalUnderBf16Conversion;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.*;
import me.bechberger.condensed.Message.StartMessage;
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

    @Test
    @SuppressWarnings("unchecked")
    public void testGCAllocAndPromoteCombiner() {
        boolean sumObjectSizes = false;
        List<RecordedEvent> recordedInNewPlabEvents = new ArrayList<>();
        List<RecordedEvent> recordedObjectAllocationEvents = new ArrayList<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter =
                    new BasicJFRWriter(
                            out,
                            Configuration.DEFAULT
                                    .withCombinePLABPromotionEvents(true)
                                    .withCombineObjectAllocationSampleEvents(true)
                                    .withSumObjectSizes(sumObjectSizes));
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("jdk.PromoteObjectInNewPLAB");
                rs.onEvent(
                        "jdk.PromoteObjectInNewPLAB",
                        event -> {
                            System.out.println(event);
                            basicJFRWriter.processEvent(event);
                            recordedInNewPlabEvents.add(event);
                        });
                rs.enable("jdk.ObjectAllocationSample");
                rs.onEvent(
                        "jdk.ObjectAllocationSample",
                        event -> {
                            System.out.println(event);
                            basicJFRWriter.processEvent(event);
                            recordedObjectAllocationEvents.add(event);
                        });
                rs.onEvent("TestEvent", e -> rs.close());

                rs.startAsync();

                // try to trigger a GC event
                // by allocating a large chunk and then calling System.gc()

                System.out.println(new byte[1024 * 1024 * 1024].length);
                System.gc();

                System.out.println(new byte[1024 * 1024].length);
                System.gc();

                new TestEvent(0).commit();

                rs.awaitTermination();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            basicJFRWriter.close();
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {

            boolean hadCombinedPlabEvent = false;
            boolean hadObjectAllocationEvent = false;

            int foundAllocClassEntries = 0;

            var message = in.readNextInstance();
            while (message != null) {
                System.out.println("Got message: " + message + " of type " + message.type());

                if (message.type().getName().equals("jdk.combined.PromoteObjectInNewPLAB")) {
                    var combined = (ReadStruct) message.value();
                    var map = (ReadList<ReadStruct>) combined.get("objectClass");
                    var gcId = (long) combined.get("gcId");
                    var forIdPerClass =
                            recordedInNewPlabEvents.stream()
                                    .filter(e -> e.getLong("gcId") == gcId)
                                    .collect(Collectors.groupingBy(e -> e.getClass("objectClass")));
                    assertEquals(
                            forIdPerClass.size(),
                            map.size(),
                            "Number of classes for GC ID " + gcId + " does not match");

                    hadCombinedPlabEvent = true;
                }

                if (message.type().getName().equals("jdk.combined.ObjectAllocationSample")) {
                    var combined = (ReadStruct) message.value();
                    var map = (ReadList<ReadStruct>) combined.get("objectClass");
                    foundAllocClassEntries += map.size();
                    hadObjectAllocationEvent = true;
                }

                message = in.readNextInstance();
            }
            assertTrue(hadCombinedPlabEvent, "No combined PLAB event found");
            assertTrue(hadObjectAllocationEvent, "No combined ObjectAllocation event found");

            var perClass =
                    recordedObjectAllocationEvents.stream()
                            .collect(Collectors.groupingBy(e -> e.getClass("objectClass")));

            assertTrue(
                    perClass.size() <= foundAllocClassEntries,
                    "Number of classes for does not match");
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    @SuppressWarnings("unchecked")
    public void testTenuringDistributionCombiner(boolean ignoreZeroSizedTenuredAges) {
        Map<Long, Map<Long, Long>> gcIdToAgeToSize = new HashMap<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter =
                    new BasicJFRWriter(
                            out,
                            Configuration.DEFAULT
                                    .withCombinePLABPromotionEvents(true)
                                    .withCombineObjectAllocationSampleEvents(true)
                                    .withCombineEventsWithoutDataLoss(true)
                                    .withIgnoreZeroSizedTenuredAges(ignoreZeroSizedTenuredAges));
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("jdk.TenuringDistribution");
                rs.onEvent(
                        "jdk.TenuringDistribution",
                        event -> {
                            System.out.println(event);
                            basicJFRWriter.processEvent(event);
                            var gcId = event.getLong("gcId");
                            var age = event.getLong("age");
                            var size = event.getLong("size");
                            gcIdToAgeToSize
                                    .computeIfAbsent(gcId, k -> new HashMap<>())
                                    .put(age, size);
                        });
                rs.onEvent("TestEvent", e -> rs.close());

                rs.startAsync();

                // try to trigger a GC event
                // by allocating a large chunk and then calling System.gc()

                System.out.println(new byte[1024 * 1024 * 1024].length);
                System.gc();

                System.out.println(new byte[1024 * 1024 * 1024].length);
                System.gc();

                new TestEvent(0).commit();

                rs.awaitTermination();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            basicJFRWriter.close();
        }
        if (gcIdToAgeToSize.isEmpty()) {
            fail("No TenuringDistribution events found, alter test");
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            boolean hadTenuringDistributionEvent = false;
            var message = in.readNextInstance();
            while (message != null) {
                System.out.println("Got message: " + message + " of type " + message.type());
                if (message.type().getName().equals("jdk.combined.TenuringDistribution")) {
                    var combined = (ReadStruct) message.value();
                    var map = (ReadList<ReadStruct>) combined.get("age");
                    var gcId = (long) combined.get("gcId");
                    var expectedAgeToSize = gcIdToAgeToSize.get(gcId);

                    for (var entry : map) {
                        var age = (long) entry.get("key");
                        var size = (long) entry.get("value");
                        assertEquals(
                                expectedAgeToSize.get(age),
                                size,
                                "Size for age " + age + " does not match");
                    }

                    if (ignoreZeroSizedTenuredAges) {
                        // all ages not in the map should be 0
                        for (var entry : expectedAgeToSize.entrySet()) {
                            assertEquals(
                                    entry.getValue() == 0,
                                    map.stream()
                                            .noneMatch(e -> e.get("key").equals(entry.getKey())));
                        }
                    } else {
                        assertEquals(
                                expectedAgeToSize.size(),
                                map.size(),
                                "Number of ages does not match");
                    }

                    hadTenuringDistributionEvent = true;
                }
                message = in.readNextInstance();
            }
            assertTrue(
                    hadTenuringDistributionEvent, "No combined TenuringDistribution event found");
        }
    }

    @ParameterizedTest
    @CsvSource({"1_000_000, false", "1_000, false", "1_000, true"})
    @SuppressWarnings("unchecked")
    public void testGCPhasePauseLevel1Combiner(
            long durationTicksPerSecond, boolean ignoreTooShortGCPauses) {
        Map<Long, Map<String, Duration>> gcIdToNameToDuration = new HashMap<>();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        var config =
                Configuration.DEFAULT
                        .withCombinePLABPromotionEvents(true)
                        .withCombineObjectAllocationSampleEvents(true)
                        .withCombineEventsWithoutDataLoss(true)
                        .withDurationTicksPerSecond(durationTicksPerSecond)
                        .withIgnoreTooShortGCPauses(ignoreTooShortGCPauses);
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out, config);
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("jdk.GCPhasePauseLevel1");
                rs.onEvent(
                        "jdk.GCPhasePauseLevel1",
                        event -> {
                            System.out.println(event);
                            basicJFRWriter.processEvent(event);
                            var gcId = event.getLong("gcId");
                            var name = event.getString("name");
                            var duration = event.getDuration();
                            gcIdToNameToDuration
                                    .computeIfAbsent(gcId, k -> new HashMap<>())
                                    .put(name, duration);
                        });
                rs.onEvent("TestEvent", e -> rs.close());

                rs.startAsync();

                // try to trigger a GC event
                // by allocating a large chunk and then calling System.gc()

                System.out.println(new byte[1024 * 1024 * 1024].length);
                System.gc();

                System.out.println(new byte[1024 * 1024 * 1024].length);
                System.gc();

                new TestEvent(0).commit();

                rs.awaitTermination();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            basicJFRWriter.close();
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            boolean hadGCPhasePauseLevel1Event = false;
            var message = in.readNextInstance();
            while (message != null) {
                System.out.println("Got message: " + message + " of type " + message.type());
                if (message.type().getName().equals("jdk.combined.GCPhasePauseLevel1")) {
                    var combined = (ReadStruct) message.value();
                    var map = (ReadList<ReadStruct>) combined.get("name");
                    var gcId = (long) combined.get("gcId");
                    var nameToDuration = gcIdToNameToDuration.get(gcId);

                    for (var entry : map) {
                        var name = (String) entry.get("key");
                        var duration = Duration.ofNanos((Long) entry.get("value"));
                        Asserters.assertEquals(
                                nameToDuration.get(name),
                                duration,
                                config.durationTicksPerSecond(),
                                "Duration for name " + name + " does not match");
                    }

                    if (ignoreTooShortGCPauses) {
                        // assert that all durations that are not in the map are equal to 0 under
                        // the ticks
                        for (var entry : nameToDuration.entrySet()) {
                            var notInMap =
                                    map.stream()
                                            .noneMatch(e -> e.get("key").equals(entry.getKey()));
                            if (notInMap) {
                                Asserters.assertEquals(
                                        Duration.ZERO,
                                        entry.getValue(),
                                        config.durationTicksPerSecond(),
                                        "Duration for name " + entry.getKey() + " is not zero");
                            } else {
                                var actualDuration =
                                        map.stream()
                                                .filter(e -> e.get("key").equals(entry.getKey()))
                                                .findFirst()
                                                .orElseThrow()
                                                .get("value");
                                assertNotEquals(
                                        0L,
                                        actualDuration,
                                        "Duration for name " + entry.getKey() + " is zero");
                            }
                        }
                    } else {
                        assertEquals(
                                nameToDuration.size(),
                                map.size(),
                                "Number of GCPhasePauseLevel1 events does not match");
                    }

                    hadGCPhasePauseLevel1Event = true;
                }

                message = in.readNextInstance();
            }
            assertTrue(hadGCPhasePauseLevel1Event, "No combined GCPhasePauseLevel1 event found");
        }
    }
}
