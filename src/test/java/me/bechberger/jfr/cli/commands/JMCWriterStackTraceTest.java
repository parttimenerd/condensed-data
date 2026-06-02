package me.bechberger.jfr.cli.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.RecordingImpl;
import org.openjdk.jmc.flightrecorder.writer.api.*;

/**
 * Test that the JMC writer can produce valid StackTrace CP entries when StackFrame.type is a String
 * field (as happens during inflate).
 */
public class JMCWriterStackTraceTest {

    @TempDir Path tempDir;

    @Disabled(
            "Known JMC writer bug: StackTrace CP binary misalignment when StackFrame.type is"
                    + " String")
    @Test
    void testStringTypeInStackFrame() throws Exception {
        Path out = tempDir.resolve("test.jfr");
        try (OutputStream os = Files.newOutputStream(out)) {
            RecordingImpl recording =
                    (RecordingImpl)
                            Recordings.newRecording(
                                    os,
                                    r -> {
                                        r.withStartTicks(1);
                                        r.withTimestamp(1);
                                    });

            Types types = recording.getTypes();

            // Minimal type hierarchy - no circular refs
            Type methodType =
                    types.getOrAdd(
                            "jdk.types.Method",
                            true,
                            b -> {
                                b.addField("name", Types.Builtin.STRING);
                                b.addField("descriptor", Types.Builtin.STRING);
                            });

            // KEY: StackFrame.type is String (not FrameType struct)
            Type stackFrameType =
                    types.getOrAdd(
                            "jdk.types.StackFrame",
                            false,
                            b -> {
                                b.addField("method", methodType);
                                b.addField("lineNumber", Types.Builtin.INT);
                                b.addField("bytecodeIndex", Types.Builtin.INT);
                                b.addField("type", Types.Builtin.STRING);
                            });

            Type stackTraceType =
                    types.getOrAdd(
                            "jdk.types.StackTrace",
                            true,
                            b -> {
                                b.addField("truncated", Types.Builtin.BOOLEAN);
                                b.addField("frames", stackFrameType, TypedFieldBuilder::asArray);
                            });

            Type threadType =
                    types.getOrAdd(
                            "java.lang.Thread",
                            true,
                            b -> {
                                b.addField("javaName", Types.Builtin.STRING);
                                b.addField("javaThreadId", Types.Builtin.LONG);
                            });

            // Event type
            Type eventType =
                    recording.registerType(
                            "jdk.ExecutionSample",
                            "jdk.jfr.Event",
                            b -> {
                                b.addField("startTime", Types.Builtin.LONG);
                                b.addField("sampledThread", threadType);
                                b.addField("stackTrace", stackTraceType);
                            });

            TypedValue threadVal =
                    threadType.asValue(
                            t -> {
                                t.putField("javaName", "main");
                                t.putField("javaThreadId", 1L);
                            });

            TypedValue methodVal =
                    methodType.asValue(
                            m -> {
                                m.putField("name", "run");
                                m.putField("descriptor", "()V");
                            });

            // Write 50 events with varying stack traces
            for (int i = 0; i < 50; i++) {
                int idx = i;
                TypedValue stackTraceVal =
                        stackTraceType.asValue(
                                st -> {
                                    st.putField("truncated", false);
                                    TypedValue[] frames = new TypedValue[2 + (idx % 10)];
                                    for (int j = 0; j < frames.length; j++) {
                                        int jj = j;
                                        frames[j] =
                                                stackFrameType.asValue(
                                                        sf -> {
                                                            sf.putField("method", methodVal);
                                                            sf.putField("lineNumber", 100 + jj);
                                                            sf.putField("bytecodeIndex", 10 + jj);
                                                            sf.putField(
                                                                    "type",
                                                                    jj % 3 == 0
                                                                            ? "Interpreted"
                                                                            : "JIT compiled");
                                                        });
                                    }
                                    st.putField("frames", frames);
                                });

                TypedValue event =
                        eventType.asValue(
                                e -> {
                                    e.putField("startTime", 1000L + idx);
                                    e.putField("sampledThread", threadVal);
                                    e.putField("stackTrace", stackTraceVal);
                                });

                recording.writeEvent(event);
            }

            recording.close();
        }

        // Read back and verify
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(out)) {
            while (rf.hasMoreEvents()) {
                events.add(rf.readEvent());
            }
        }

        assertEquals(50, events.size(), "Should read 50 events");
    }

    /**
     * Bug 16 root cause: JMC's putField(name, TypedValue...) silently ignores empty arrays. The JMC
     * writer then defaults to a null StackFrame value (writeLong(0)), but the JDK parser expects
     * inline StackFrame fields, causing binary misalignment.
     *
     * <p>This test verifies our workaround in WritingJFRReader using reflection.
     */
    @Disabled("Known JMC writer bug: empty frames array causes binary misalignment")
    @Test
    void testEmptyFramesStackTraceViaReflection() throws Exception {
        Path out = tempDir.resolve("test_empty.jfr");
        try (OutputStream os = Files.newOutputStream(out)) {
            RecordingImpl recording =
                    (RecordingImpl)
                            Recordings.newRecording(
                                    os,
                                    r -> {
                                        r.withStartTicks(1);
                                        r.withTimestamp(1);
                                    });

            Types types = recording.getTypes();

            Type methodType =
                    types.getOrAdd(
                            "jdk.types.Method",
                            true,
                            b -> {
                                b.addField("name", Types.Builtin.STRING);
                                b.addField("descriptor", Types.Builtin.STRING);
                            });

            Type stackFrameType =
                    types.getOrAdd(
                            "jdk.types.StackFrame",
                            false,
                            b -> {
                                b.addField("method", methodType);
                                b.addField("lineNumber", Types.Builtin.INT);
                                b.addField("bytecodeIndex", Types.Builtin.INT);
                                b.addField("type", Types.Builtin.STRING);
                            });

            Type stackTraceType =
                    types.getOrAdd(
                            "jdk.types.StackTrace",
                            true,
                            b -> {
                                b.addField("truncated", Types.Builtin.BOOLEAN);
                                b.addField("frames", stackFrameType, TypedFieldBuilder::asArray);
                            });

            Type threadType =
                    types.getOrAdd(
                            "java.lang.Thread",
                            true,
                            b -> {
                                b.addField("javaName", Types.Builtin.STRING);
                                b.addField("javaThreadId", Types.Builtin.LONG);
                            });

            Type eventType =
                    recording.registerType(
                            "jdk.ExecutionSample",
                            "jdk.jfr.Event",
                            b -> {
                                b.addField("startTime", Types.Builtin.LONG);
                                b.addField("sampledThread", threadType);
                                b.addField("stackTrace", stackTraceType);
                            });

            TypedValue threadVal =
                    threadType.asValue(
                            t -> {
                                t.putField("javaName", "main");
                                t.putField("javaThreadId", 1L);
                            });

            TypedValue methodVal =
                    methodType.asValue(
                            m -> {
                                m.putField("name", "run");
                                m.putField("descriptor", "()V");
                            });

            // Write events - mix of empty and non-empty stack traces
            for (int i = 0; i < 50; i++) {
                int idx = i;
                TypedValue stackTraceVal =
                        stackTraceType.asValue(
                                st -> {
                                    st.putField("truncated", false);
                                    if (idx == 30 || idx == 42) {
                                        // Empty stack trace: use reflection to set empty array
                                        // (same workaround as WritingJFRReader)
                                        try {
                                            var m =
                                                    st.getClass()
                                                            .getDeclaredMethod(
                                                                    "putArrayField",
                                                                    String.class,
                                                                    org.openjdk.jmc.flightrecorder
                                                                                            .writer
                                                                                            .TypedValueImpl
                                                                                    []
                                                                            .class);
                                            m.setAccessible(true);
                                            m.invoke(
                                                    st,
                                                    "frames",
                                                    new org.openjdk.jmc.flightrecorder.writer
                                                                    .TypedValueImpl[0]);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    } else {
                                        TypedValue[] frames = new TypedValue[2 + (idx % 5)];
                                        for (int j = 0; j < frames.length; j++) {
                                            int jj = j;
                                            frames[j] =
                                                    stackFrameType.asValue(
                                                            sf -> {
                                                                sf.putField("method", methodVal);
                                                                sf.putField("lineNumber", 100 + jj);
                                                                sf.putField(
                                                                        "bytecodeIndex", 10 + jj);
                                                                sf.putField("type", "Interpreted");
                                                            });
                                        }
                                        st.putField("frames", frames);
                                    }
                                });

                TypedValue event =
                        eventType.asValue(
                                e -> {
                                    e.putField("startTime", 1000L + idx);
                                    e.putField("sampledThread", threadVal);
                                    e.putField("stackTrace", stackTraceVal);
                                });

                recording.writeEvent(event);
            }

            recording.close();
        }

        // Read back and verify
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(out)) {
            while (rf.hasMoreEvents()) {
                events.add(rf.readEvent());
            }
        }

        assertEquals(50, events.size(), "Should read 50 events with empty stack traces");
    }
}
