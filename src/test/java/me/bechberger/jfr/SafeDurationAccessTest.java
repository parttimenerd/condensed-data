package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests that the condense pipeline handles JFR files where some event chunks have missing
 * {@code @Timespan} annotations on the "duration" field. This is a regression test for Bug 35.
 *
 * <p>Some JFR files (e.g. multi-chunk files from non-standard tools or older JVMs) may have
 * inconsistent metadata: some chunks annotate the "duration" field with {@code @Timespan}, others
 * don't. The JDK's {@code RecordedObject.getDuration(String)} throws {@code
 * IllegalArgumentException} when the annotation is missing, while {@code
 * RecordedEvent.getDuration()} (no-arg) always works.
 */
public class SafeDurationAccessTest {

    private static final Path BLA_JFR = Path.of("bla.jfr");

    static boolean blaJfrExists() {
        return Files.exists(BLA_JFR);
    }

    /**
     * Condensing bla.jfr should succeed despite events with missing {@code @Timespan} on duration.
     * Before the fix, this would throw: "Attempt to get long field 'duration' with missing
     * {@code @Timespan}"
     */
    @Test
    @EnabledIf("blaJfrExists")
    public void testCondenseBlaJfrDoesNotCrashOnMissingTimespan() {
        var baos = new ByteArrayOutputStream();
        assertDoesNotThrow(
                () -> {
                    try (var out =
                            new CondensedOutputStream(
                                    baos,
                                    new StartMessage(
                                            1, "test", "0.1", "default", Compression.NONE))) {
                        var writer = new BasicJFRWriter(out, Configuration.DEFAULT);
                        try (var r = new RecordingFile(BLA_JFR)) {
                            while (r.hasMoreEvents()) {
                                writer.processEvent(r.readEvent());
                            }
                        }
                        writer.close();
                    }
                },
                "Condensing bla.jfr should not crash on events with missing @Timespan");
        assertTrue(baos.size() > 0, "Output should not be empty");
    }

    /**
     * Verify that events in bla.jfr with inconsistent @Timespan annotations are handled: some
     * chunks have the annotation, some don't. The no-arg getDuration() should always work.
     */
    @Test
    @EnabledIf("blaJfrExists")
    public void testBlaJfrHasEventsWithMissingTimespanAnnotation() throws Exception {
        boolean foundMissing = false;
        boolean foundWorking = false;
        try (var r = new RecordingFile(BLA_JFR)) {
            while (r.hasMoreEvents()) {
                var e = r.readEvent();
                var type = e.getEventType();
                var durationField = type.getField("duration");
                if (durationField == null) continue;
                boolean hasTimespan =
                        durationField.getAnnotationElements().stream()
                                .anyMatch(a -> a.getTypeName().equals("jdk.jfr.Timespan"));
                if (!hasTimespan) {
                    // The no-arg getDuration() should always work
                    assertDoesNotThrow(
                            () -> e.getDuration(),
                            "RecordedEvent.getDuration() should work even without @Timespan");
                    foundMissing = true;
                } else {
                    foundWorking = true;
                }
                if (foundMissing && foundWorking) break;
            }
        }
        assertTrue(foundMissing, "bla.jfr should have events with missing @Timespan");
    }

    /**
     * Bug 168: When condensing bla.jfr (which has missing annotations), the startTime field should
     * still be stored with the 'timestamp' type and TIMESTAMP_REDUCTION, not as a raw 'long'. After
     * reading back, the value should be an Instant, not a Long.
     */
    @Test
    @EnabledIf("blaJfrExists")
    public void testCondensedBlaJfrHasTimestampTypeForStartTime() throws Exception {
        var baos = new ByteArrayOutputStream();
        try (var out =
                new CondensedOutputStream(
                        baos, new StartMessage(1, "test", "0.1", "default", Compression.NONE))) {
            var writer = new BasicJFRWriter(out, Configuration.DEFAULT);
            try (var r = new RecordingFile(BLA_JFR)) {
                while (r.hasMoreEvents()) {
                    writer.processEvent(r.readEvent());
                }
            }
            writer.close();
        }

        try (var in = new me.bechberger.condensed.CondensedInputStream(baos.toByteArray())) {
            var reader = new BasicJFRReader(in);
            var struct = reader.readNextEvent();
            boolean foundTimestampField = false;
            while (struct != null) {
                var startTimeField =
                        struct.getType().getFields().stream()
                                .filter(f -> f.name().equals("startTime"))
                                .findFirst();
                if (startTimeField.isPresent()) {
                    var field = startTimeField.get();
                    assertEquals(
                            "timestamp",
                            field.type().getName(),
                            "startTime field in event "
                                    + struct.getType().getName()
                                    + " should have type 'timestamp', not '"
                                    + field.type().getName()
                                    + "'");
                    var value = struct.get("startTime");
                    assertInstanceOf(
                            java.time.Instant.class,
                            value,
                            "startTime value in event "
                                    + struct.getType().getName()
                                    + " should be Instant, not "
                                    + (value != null ? value.getClass().getSimpleName() : "null"));
                    foundTimestampField = true;
                    break;
                }
                struct = reader.readNextEvent();
            }
            assertTrue(foundTimestampField, "Should find at least one event with startTime field");
        }
    }

    /**
     * Bug 168: Duration fields in bla.jfr should be stored with 'timespan' type, not as raw 'long',
     * even when @Timespan annotation is missing.
     */
    @Test
    @EnabledIf("blaJfrExists")
    public void testCondensedBlaJfrHasTimespanTypeForDuration() throws Exception {
        var baos = new ByteArrayOutputStream();
        try (var out =
                new CondensedOutputStream(
                        baos, new StartMessage(1, "test", "0.1", "default", Compression.NONE))) {
            var writer = new BasicJFRWriter(out, Configuration.DEFAULT);
            try (var r = new RecordingFile(BLA_JFR)) {
                while (r.hasMoreEvents()) {
                    writer.processEvent(r.readEvent());
                }
            }
            writer.close();
        }

        try (var in = new me.bechberger.condensed.CondensedInputStream(baos.toByteArray())) {
            var reader = new BasicJFRReader(in);
            var struct = reader.readNextEvent();
            boolean foundDurationField = false;
            while (struct != null) {
                var durationField =
                        struct.getType().getFields().stream()
                                .filter(f -> f.name().equals("duration"))
                                .findFirst();
                if (durationField.isPresent()) {
                    var field = durationField.get();
                    assertEquals(
                            "timespan",
                            field.type().getName(),
                            "duration field in event "
                                    + struct.getType().getName()
                                    + " should have type 'timespan', not '"
                                    + field.type().getName()
                                    + "'");
                    var value = struct.get("duration");
                    assertInstanceOf(
                            java.time.Duration.class,
                            value,
                            "duration value in event "
                                    + struct.getType().getName()
                                    + " should be Duration, not "
                                    + (value != null ? value.getClass().getSimpleName() : "null"));
                    foundDurationField = true;
                    break;
                }
                struct = reader.readNextEvent();
            }
            assertTrue(foundDurationField, "Should find at least one event with duration field");
        }
    }
}
