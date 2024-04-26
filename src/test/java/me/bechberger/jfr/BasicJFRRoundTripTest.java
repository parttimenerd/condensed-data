package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import org.junit.jupiter.api.Test;

public class BasicJFRRoundTripTest {

    @Name("TestEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace(true)
    static class TestEvent extends Event {}

    @Test
    public void testTestEventRountTrip() throws InterruptedException {
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
                BasicJFRWriterTest.TestEvent testEvent = new BasicJFRWriterTest.TestEvent();
                testEvent.commit();
                rs.awaitTermination();
            }
        }
        try (var in = new CondensedInputStream(outputStream.toByteArray())) {
            BasicJFRReader basicJFRReader = new BasicJFRReader(in);
            var readEvent = basicJFRReader.readNextEvent();
            assertNotNull(readEvent);
            assertEquals("TestEvent", readEvent.getType().getName());
            assertEquals("Label: Description", readEvent.getType().getDescription());
            assertEquals(ttps, basicJFRReader.getConfiguration().timeStampTicksPerSecond());
        }
    }
}
