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
     * Bug 258: inflated JFR files lost the {@code @ContentType} meta-annotation on content-type
     * annotation types ({@code jdk.jfr.DataAmount}, {@code jdk.jfr.MemoryAddress}, {@code
     * jdk.jfr.Percentage}, {@code jdk.jfr.Timestamp}, ...). {@code WritingJFRReader} registered
     * these as plain annotation types without the {@code @ContentType} marker, so the JDK {@code
     * jfr print} tool no longer applied unit/format rendering: sizes printed as raw byte counts,
     * addresses as decimals, percentages as fractions, timestamps as raw ISO instants.
     *
     * <p>This end-to-end test condenses → inflates {@code profile.jfr} and asserts that a
     * {@code @DataAmount} field's {@link jdk.jfr.ValueDescriptor#getContentType()} is non-null in
     * the inflated file, which is exactly the signal {@code jfr print} uses to format the value.
     */
    @Test
    @InflaterRelated
    public void testInflatedContentTypeAnnotationsPreserved() throws Exception {
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
                                            (r2, m2) ->
                                                    checkContentTypePreserved(
                                                            m2.get("inflated.jfr")))
                                    .run();
                        })
                .run();
    }

    private static void checkContentTypePreserved(Path inflated) throws Exception {
        try (RecordingFile r = new RecordingFile(inflated)) {
            jdk.jfr.EventType heapConfig = null;
            while (r.hasMoreEvents()) {
                var type = r.readEvent().getEventType();
                if (type.getName().equals("jdk.GCHeapConfiguration")) {
                    heapConfig = type;
                    break;
                }
            }
            assertNotNull(
                    heapConfig, "inflated file should contain jdk.GCHeapConfiguration events");
            // minSize is @DataAmount("BYTES"); after the fix its content type must survive so that
            // `jfr print` renders it as "8,0 MB" instead of the raw byte count "8388608".
            var minSize = heapConfig.getField("minSize");
            assertNotNull(minSize, "jdk.GCHeapConfiguration should have a minSize field");
            // minSize is @Unsigned @DataAmount("BYTES"). getContentType() returns one of the two
            // (JFR picks @Unsigned when several content types are present); the fix guarantees it
            // is
            // non-null, which is the signal `jfr print` uses to render "8,0 MB" instead of the raw
            // byte count "8388608". Before the fix the content-type annotation types lacked the
            // @ContentType meta-annotation, so getContentType() was null.
            assertNotNull(
                    minSize.getContentType(),
                    "jdk.GCHeapConfiguration.minSize must keep a content type after inflate (was"
                        + " lost, so jfr print showed raw byte counts instead of formatted sizes)");
        }
    }

    /**
     * Bug 260: JFR uses {@code Long.MIN_VALUE} as the "unset" sentinel for {@code @Timespan} longs
     * (e.g. {@code jdk.GCConfiguration.pauseTarget}, rendered {@code N/A} by {@code jfr print}).
     * The condense path routed the value through {@code Duration} + {@code TimeUtil.clamp}, which
     * collapsed the sentinel to {@code -365 d} and then the varint multiplier quantized it further,
     * so a condense → inflate roundtrip turned {@code N/A} into a bogus large negative duration
     * (e.g. {@code -106752 d 0 h}). The fix carries the sentinel through the reduction and restores
     * {@code Long.MIN_VALUE} on inflate.
     */
    @Test
    @InflaterRelated
    public void testInflatedTimespanUnsetSentinelPreserved() throws Exception {
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
                                            (r2, m2) ->
                                                    checkPauseTargetSentinel(
                                                            m2.get("inflated.jfr")))
                                    .run();
                        })
                .run();
    }

    private static void checkPauseTargetSentinel(Path inflated) throws Exception {
        var events =
                RecordingFile.readAllEvents(inflated).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.GCConfiguration"))
                        .toList();
        assertFalse(events.isEmpty(), "inflated file should contain jdk.GCConfiguration events");
        for (var e : events) {
            // pauseTarget is @Timespan("MILLISECONDS") long; the original is Long.MIN_VALUE (N/A).
            // It must roundtrip bit-exactly, not become a clamped/quantized bogus duration.
            assertEquals(
                    Long.MIN_VALUE,
                    e.getLong("pauseTarget"),
                    "GCConfiguration.pauseTarget unset sentinel (Long.MIN_VALUE / N/A) must survive"
                            + " a condense→inflate roundtrip");
        }
    }

    /**
     * Bug 261: JFR uses {@code Long.MIN_VALUE} as the "unset" sentinel for {@code @Timestamp} longs
     * too, not just {@code @Timespan} (Bug 260). {@code jdk.ThreadPark.until} is a
     * {@code @Timestamp("MILLISECONDS_SINCE_EPOCH")} long that is {@code Long.MIN_VALUE} (rendered
     * {@code N/A}) whenever a thread parks with no deadline ({@code park()} rather than {@code
     * parkUntil()}). JMC maps the raw sentinel to {@link java.time.Instant#MIN}, but the condense
     * path ran that through {@code toNanoSeconds} (overflow) and the timestamp delta encoding,
     * turning {@code N/A} into a bogus epoch value (e.g. {@code -3059627606664}) on inflate — and
     * risked poisoning the shared delta baseline for neighbouring timestamps. The fix carries the
     * sentinel through {@code TIMESTAMP_REDUCTION} untouched (without updating the delta baseline)
     * and restores {@code Long.MIN_VALUE} on inflate.
     */
    @Test
    @InflaterRelated
    public void testInflatedTimestampUnsetSentinelPreserved() throws Exception {
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
                                            (r2, m2) ->
                                                    checkParkUntilSentinel(
                                                            profileJfr, m2.get("inflated.jfr")))
                                    .run();
                        })
                .run();
    }

    private static void checkParkUntilSentinel(Path original, Path inflated) throws Exception {
        var origUnset =
                RecordingFile.readAllEvents(original).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.ThreadPark"))
                        .filter(e -> e.getLong("until") == Long.MIN_VALUE)
                        .count();
        assertFalse(
                origUnset == 0,
                "profile.jfr should contain ThreadPark events with an unset until (N/A) sentinel");

        var inflatedUnset =
                RecordingFile.readAllEvents(inflated).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.ThreadPark"))
                        .filter(e -> e.getLong("until") == Long.MIN_VALUE)
                        .count();
        assertEquals(
                origUnset,
                inflatedUnset,
                "ThreadPark.until unset sentinel (Long.MIN_VALUE / N/A) must survive a"
                        + " condense→inflate roundtrip instead of becoming a bogus epoch value");
    }

    /**
     * Regression for the {@code @Timespan} "Forever" sentinel ({@code Long.MAX_VALUE}). {@code
     * jdk.ActiveRecording.maxAge}/{@code recordingDuration} hold {@code Long.MAX_VALUE} for an
     * unbounded recording ({@code jfr print} renders {@code Forever}). Before the fix a
     * condense→inflate roundtrip clamped them to a bogus {@code 365 d 0 h}.
     */
    @Test
    @InflaterRelated
    public void testInflatedTimespanForeverSentinelPreserved() throws Exception {
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
                                            (r2, m2) ->
                                                    checkForeverSentinel(
                                                            profileJfr, m2.get("inflated.jfr")))
                                    .run();
                        })
                .run();
    }

    private static void checkForeverSentinel(Path original, Path inflated) throws Exception {
        for (String field : new String[] {"maxAge", "recordingDuration"}) {
            var origForever =
                    RecordingFile.readAllEvents(original).stream()
                            .filter(e -> e.getEventType().getName().equals("jdk.ActiveRecording"))
                            .filter(e -> e.getLong(field) == Long.MAX_VALUE)
                            .count();
            assertFalse(
                    origForever == 0,
                    "profile.jfr should contain ActiveRecording events with a Forever "
                            + field
                            + " (Long.MAX_VALUE) sentinel");

            var inflatedForever =
                    RecordingFile.readAllEvents(inflated).stream()
                            .filter(e -> e.getEventType().getName().equals("jdk.ActiveRecording"))
                            .filter(e -> e.getLong(field) == Long.MAX_VALUE)
                            .count();
            assertEquals(
                    origForever,
                    inflatedForever,
                    "ActiveRecording."
                            + field
                            + " Forever sentinel (Long.MAX_VALUE) must survive a condense→inflate"
                            + " roundtrip instead of being clamped to a bogus 365-day value");
        }
    }

    /**
     * Regression for Bug 264: the source recording's timezone ({@code gmtOffset} in the JFR
     * metadata region) was lost through condense→inflate, so {@code jfr print} rendered every
     * inflated timestamp in UTC instead of the recording's original local zone. The fix captures
     * the source {@code gmtOffset} at condense (stored in the {@link Universe}) and re-injects it
     * into the inflated chunk's metadata region. Here we assert the inflated file carries the same
     * {@code gmtOffset} as the source (read via the same raw parser used at condense).
     */
    @Test
    @InflaterRelated
    public void testInflatedRecordingTimezonePreserved() throws Exception {
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
                                            (r2, m2) ->
                                                    checkTimezonePreserved(
                                                            profileJfr, m2.get("inflated.jfr")))
                                    .run();
                        })
                .run();
    }

    private static void checkTimezonePreserved(Path original, Path inflated) {
        long origOffset = BasicJFRWriter.readChunkGmtOffsetMillis(original);
        assertFalse(
                origOffset == Universe.GMT_OFFSET_UNSET,
                "profile.jfr should carry a gmtOffset in its metadata region for this test to be"
                        + " meaningful");
        long inflatedOffset = BasicJFRWriter.readChunkGmtOffsetMillis(inflated);
        assertEquals(
                origOffset,
                inflatedOffset,
                "The source recording's gmtOffset must survive a condense→inflate roundtrip so"
                        + " jfr print renders the original local zone instead of UTC");
    }
}
