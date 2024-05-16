package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
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
    @StackTrace(true)
    static class TestEvent extends Event {}

    /** Test writing an instance of {@link TestEvent} */
    @Test
    @SuppressWarnings("unchecked")
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
            System.out.println(out.getStatistic().toPrettyString());
            var types = out.getTypeCollection();
            // ensure that there is a jdk.types.StackFrame type
            assertNotNull(types.getTypeOrNull("jdk.types.StackFrame"), "StackFrame type not found");
            // check that the type for TestEvent looks properly
            var teType = types.getTypeOrNull("TestEvent");
            assertNotNull(teType, "TestEvent type not found");
            assertEquals("TestEvent", teType.getName());
            assertEquals("[\"Label\",\"Description\"]", teType.getDescription());
            assertInstanceOf(StructType.class, teType, "TestEvent is not a struct type");
            // check that the type for TestEvent has a field named "stackTrace"
            var stackTraceField = ((StructType<?, ?>) teType).getField("stackTrace");
            assertNotNull(stackTraceField, "stackTrace field not found");
        }
        byte[] data = outputStream.toByteArray();
        System.out.println(recordedEvent.get());
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
                rs.onEvent(
                        event -> {
                            basicJFRWriter.processEvent(event);
                        });
                rs.startAsync();
                Thread.sleep(100);
                TestEvent testEvent = new TestEvent();
                testEvent.commit();
                Thread.sleep(100);
            }
            System.out.println(out.getStatistic().toPrettyString());
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
}
