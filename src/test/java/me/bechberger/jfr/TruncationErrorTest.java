package me.bechberger.jfr;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idea: Handle unexpected end of stream gracefully
 */
public class TruncationErrorTest {

    private static final int EVENT_COUNT = 1000;

    private static byte[] data;
    {
        try {
            List<RecordedEvent> recordedEvents = new ArrayList<>();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (CondensedOutputStream out =
                         new CondensedOutputStream(outputStream, Message.StartMessage.DEFAULT)) {
                BasicJFRWriter basicJFRWriter =
                        new BasicJFRWriter(out, Configuration.DEFAULT);
                try (RecordingStream rs = new RecordingStream()) {
                    rs.onEvent(
                            "TestEvent",
                            event -> {
                                basicJFRWriter.processEvent(event);
                                recordedEvents.add(event);
                                if (recordedEvents.size() == EVENT_COUNT) rs.close();
                            });

                    rs.startAsync();
                    for (int i = 0; i < EVENT_COUNT; i++) {
                        new BasicJFRRoundTripTest.TestEvent(i).commit();
                    }
                    rs.awaitTermination();
                }
            }
            data = outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertEventCounts(int truncate, int expectedMinCount, int expectedMaxCount) {
        try (CondensedInputStream in =
                     new CondensedInputStream(new ByteArrayInputStream(data, 0, data.length - truncate))) {
            List<RecordedEvent> events = WritingJFRReader.toJFREventsList(new BasicJFRReader(in,
                    BasicJFRReader.Options.DEFAULT.withIgnoreCloseErrors(true)));
            assertTrue(events.size() >= expectedMinCount, "Event count should be at least " + expectedMinCount + " but was " + events.size());
            assertTrue(events.size() <= expectedMaxCount, "Event count should be at most " + expectedMaxCount + " but was " + events.size());
        }
    }

    @Example
    public void testNormalRead() {
        assertEventCounts(0, EVENT_COUNT, EVENT_COUNT);
    }

    @Example
    public void testSomeTruncation() {
        assertEventCounts(100, EVENT_COUNT - 100, EVENT_COUNT);
    }

    @Property(tries = 20)
    public void testTruncatedReads(@ForAll @IntRange(min = 0, max = 1000) int cutOffBytes) {
        assertEventCounts(cutOffBytes, EVENT_COUNT - cutOffBytes, EVENT_COUNT);
    }
}