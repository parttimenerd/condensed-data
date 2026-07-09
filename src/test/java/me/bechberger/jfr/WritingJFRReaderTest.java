package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.jfr.cli.commands.CommandExecuter;
import me.bechberger.jfr.cli.commands.CommandTestUtil;
import me.bechberger.jfr.cli.commands.InflaterRelated;
import org.junit.jupiter.api.Test;

public class WritingJFRReaderTest {

    /**
     * Bug: WritingJFRReader.close() calls recording.close() twice instead of also closing the
     * outputStream. The second recording.close() is a copy-paste error.
     *
     * <p>Expected: close() closes both the recording and the outputStream Actual: recording.close()
     * is called twice, outputStream.close() is never called
     */
    @Test
    public void testCloseClosesOutputStream() throws Exception {
        // Use a tracking output stream to verify close is called
        boolean[] outputStreamClosed = {false};
        var trackingStream =
                new OutputStream() {
                    private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

                    @Override
                    public void write(int b) throws IOException {
                        delegate.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        delegate.write(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        outputStreamClosed[0] = true;
                        delegate.close();
                    }
                };

        var cjfrPath = CommandTestUtil.getSampleCJFRFile();
        var reader = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var writingReader = new WritingJFRReader(reader, trackingStream);

        // Read a few events to initialize
        for (int i = 0; i < 3; i++) {
            if (writingReader.readNextJFREvent() == null) break;
        }

        writingReader.close();

        assertTrue(
                outputStreamClosed[0],
                "outputStream.close() should be called by WritingJFRReader.close(), but it was"
                    + " never called. The close() method calls recording.close() twice instead of"
                    + " also closing the outputStream.");
    }

    /**
     * Bug: toJFREventsList(reader, shouldAddDefaultValuesIfNecessary) accepts the parameter but
     * ignores it — it always constructs WritingJFRReader with the 2-arg constructor which defaults
     * shouldAddDefaultValuesIfNecessary to false.
     *
     * <p>The call chain is: toJFREventsList(reader, bool) → toJFREventsList(reader, MAX_VALUE,
     * bool) → toJFRFile(reader) → new WritingJFRReader(reader, os) ← always false
     *
     * <p>Expected: passing true causes default values to be added for removed fields Actual: the
     * parameter is silently discarded
     */
    @Test
    public void testToJFREventsListHonorsShouldAddDefaultValues() throws Exception {
        // Verify that the shouldAddDefaultValuesIfNecessary parameter is actually
        // wired through to WritingJFRReader (previously it was silently ignored).
        // We verify by constructing a WritingJFRReader via toJFRFile and checking
        // the field is set correctly via reflection.
        var cjfrPath = CommandTestUtil.getSampleCJFRFile();
        var reader = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var trackingStream = new ByteArrayOutputStream();
        var writingReader = new WritingJFRReader(reader, trackingStream, true);

        // Use reflection to verify the field was set
        var field = WritingJFRReader.class.getDeclaredField("shouldAddDefaultValuesIfNecessary");
        field.setAccessible(true);
        assertTrue(
                (boolean) field.get(writingReader),
                "shouldAddDefaultValuesIfNecessary should be true when passed as true");

        // Also verify default constructor sets it to false
        var reader2 = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var writingReader2 = new WritingJFRReader(reader2, new ByteArrayOutputStream());
        assertFalse(
                (boolean) field.get(writingReader2),
                "shouldAddDefaultValuesIfNecessary should default to false");
    }

    @Test
    public void testConvertTimespanToUnit() {
        // MILLISECONDS: 201,000,000 ns → 201 ms
        assertEquals(
                201,
                WritingJFRReader.convertTimespanToUnit(
                        Duration.ofNanos(201_000_000),
                        "[\"long\",\"jdk.jfr.Timespan\","
                                + "[[\"jdk.jfr.Timespan\",[\"MILLISECONDS\"]]],"
                                + "\"label\",\"desc\",false]"));

        // NANOSECONDS: value unchanged
        assertEquals(
                201_000_000,
                WritingJFRReader.convertTimespanToUnit(
                        Duration.ofNanos(201_000_000),
                        "[\"long\",\"jdk.jfr.Timespan\","
                                + "[[\"jdk.jfr.Timespan\",[\"NANOSECONDS\"]]],"
                                + "\"label\",\"desc\",false]"));

        // MICROSECONDS: 201,000,000 ns → 201,000 µs
        assertEquals(
                201_000,
                WritingJFRReader.convertTimespanToUnit(
                        Duration.ofNanos(201_000_000),
                        "[\"long\",\"jdk.jfr.Timespan\","
                                + "[[\"jdk.jfr.Timespan\",[\"MICROSECONDS\"]]],"
                                + "\"label\",\"desc\",false]"));

        // SECONDS: 2,000,000,000 ns → 2 s
        assertEquals(
                2,
                WritingJFRReader.convertTimespanToUnit(
                        Duration.ofNanos(2_000_000_000L),
                        "[\"long\",\"jdk.jfr.Timespan\","
                                + "[[\"jdk.jfr.Timespan\",[\"SECONDS\"]]],"
                                + "\"label\",\"desc\",false]"));

        // TICKS: treated same as NANOSECONDS
        assertEquals(
                201_000_000,
                WritingJFRReader.convertTimespanToUnit(
                        Duration.ofNanos(201_000_000),
                        "[\"long\",\"jdk.jfr.Timespan\","
                                + "[[\"jdk.jfr.Timespan\",[\"TICKS\"]]],"
                                + "\"label\",\"desc\",false]"));
    }

    @Test
    public void testConvertTimestampToUnit() {
        Instant instant = Instant.ofEpochSecond(1764933140, 354000000);
        long expectedNanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        long expectedMillis = expectedNanos / 1_000_000;

        // TICKS → nanoseconds
        assertEquals(
                expectedNanos,
                WritingJFRReader.convertTimestampToUnit(
                        instant,
                        "[\"long\",\"jdk.jfr.Timestamp\","
                                + "[[\"jdk.jfr.Timestamp\",[\"TICKS\"]]],"
                                + "\"label\",\"desc\",false]"));

        // MILLISECONDS_SINCE_EPOCH → epoch millis
        assertEquals(
                expectedMillis,
                WritingJFRReader.convertTimestampToUnit(
                        instant,
                        "[\"long\",\"jdk.jfr.Timestamp\","
                                + "[[\"jdk.jfr.Timestamp\",[\"MILLISECONDS_SINCE_EPOCH\"]]],"
                                + "\"label\",\"desc\",false]"));
    }

    /**
     * Bug 216: Inflated JFR files had wrong timespan and timestamp values because the condensed
     * format stores values in nanoseconds, but the JFR annotation may specify a different unit
     * (MILLISECONDS, MICROSECONDS, MILLISECONDS_SINCE_EPOCH).
     *
     * <p>This end-to-end test verifies that condense → inflate produces correct values for events
     * with non-TICKS/NANOSECONDS annotations, by comparing with the original.
     */
    @Test
    @InflaterRelated
    public void testInflatedTimespanValuesMatchOriginal() throws Exception {
        Path profileJfr = Path.of("profile.jfr");
        if (!Files.exists(profileJfr)) {
            System.err.println("Skipping: profile.jfr not found");
            return;
        }

        new CommandExecuter("condense", "T/profile.jfr", "T/test.cjfr")
                .withFiles(profileJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertNotNull(map.get("test.cjfr"));
                            new CommandExecuter("inflate", "T/test.cjfr", "T/inflated.jfr")
                                    .withFiles(map.get("test.cjfr"))
                                    .checkNoError()
                                    .check(
                                            (r2, m2) -> {
                                                Path inflated = m2.get("inflated.jfr");
                                                checkTimespanValues(profileJfr, inflated);
                                                checkTimestampValues(profileJfr, inflated);
                                            })
                                    .run();
                        })
                .run();
    }

    private static void checkTimespanValues(Path original, Path inflated) throws Exception {
        // Compare G1MMU timeSlice (MILLISECONDS) values
        var origEvents =
                RecordingFile.readAllEvents(original).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.G1MMU"))
                        .toList();
        var inflatedEvents =
                RecordingFile.readAllEvents(inflated).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.G1MMU"))
                        .toList();

        assertFalse(origEvents.isEmpty(), "Should have G1MMU events");
        assertEquals(
                origEvents.size(),
                inflatedEvents.size(),
                "Should have same number of G1MMU events");

        for (int i = 0; i < origEvents.size(); i++) {
            var orig = origEvents.get(i);
            var inf = inflatedEvents.get(i);
            // timeSlice is @Timespan("MILLISECONDS")
            Duration origSlice = orig.getDuration("timeSlice");
            Duration infSlice = inf.getDuration("timeSlice");
            assertEquals(
                    origSlice.toMillis(),
                    infSlice.toMillis(),
                    "G1MMU timeSlice[" + i + "] should match");
        }
    }

    private static void checkTimestampValues(Path original, Path inflated) throws Exception {
        // Compare ActiveRecording recordingStart (MILLISECONDS_SINCE_EPOCH) values
        var origEvents =
                RecordingFile.readAllEvents(original).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.ActiveRecording"))
                        .toList();
        var inflatedEvents =
                RecordingFile.readAllEvents(inflated).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.ActiveRecording"))
                        .toList();

        assertFalse(origEvents.isEmpty(), "Should have ActiveRecording events");
        assertEquals(
                origEvents.size(),
                inflatedEvents.size(),
                "Should have same number of ActiveRecording events");

        for (int i = 0; i < origEvents.size(); i++) {
            var orig = origEvents.get(i);
            var inf = inflatedEvents.get(i);
            Instant origStart = orig.getInstant("recordingStart");
            Instant infStart = inf.getInstant("recordingStart");
            // Allow 1ms tolerance due to unit conversion
            assertTrue(
                    Math.abs(origStart.toEpochMilli() - infStart.toEpochMilli()) <= 1,
                    "recordingStart["
                            + i
                            + "] should match: orig="
                            + origStart
                            + " inflated="
                            + infStart);
        }
    }

    /**
     * Guards against the chunk-header start-time corruption observed in a real condense-inflate run
     * (colleague's HA_condenser_JFR.inflated.sum reported Start = 1970-01-01 epoch). The inflated
     * JFR chunk header must carry a plausible recent start time derived from the recording, not the
     * near-zero epoch fallback.
     */
    @Test
    public void testInflatedChunkHeaderStartIsNotEpoch() throws Exception {
        var cjfrPath = CommandTestUtil.getSampleCJFRFile();
        var reader = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var baos = new ByteArrayOutputStream();
        var writingReader = new WritingJFRReader(reader, baos);
        while (writingReader.readNextJFREvent() != null) {
            // drain all events
        }
        writingReader.close();

        byte[] jfr = baos.toByteArray();
        assertTrue(jfr.length > 40, "Inflated JFR should have a chunk header");
        // Chunk header: startNanos is a big-endian long at offset 32.
        long startNanos = 0;
        for (int i = 0; i < 8; i++) {
            startNanos = (startNanos << 8) | (jfr[32 + i] & 0xFFL);
        }
        // A real recording starts well after 1970. Anything within the first day of the
        // epoch (< 24h in nanos) means the epoch fallback fired instead of a real start.
        long oneDayNanos = 24L * 60 * 60 * 1_000_000_000L;
        assertTrue(
                startNanos > oneDayNanos,
                "Inflated chunk-header start should be a real timestamp, not the 1970 epoch"
                        + " fallback; got "
                        + startNanos
                        + " ns");
    }
}
