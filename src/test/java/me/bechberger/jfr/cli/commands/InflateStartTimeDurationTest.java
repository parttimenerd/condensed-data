package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.WritingJFRReader;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for Bug: {@code inflate} produces JFR files with the wrong recording start date
 * and duration in the chunk header.
 *
 * <p>Unlike {@link ChunkHeaderTest} (which uses loose bounds and depends on an external {@code
 * profile.jfr}), these tests generate a real JFR file in-process and compare the inflated chunk
 * header directly against the <em>original</em> JFR's chunk header with tight tolerances.
 */
@InflaterRelated
public class InflateStartTimeDurationTest {

    /** JFR chunk header: magic(4)+version(4)+chunkSize(8)+cpOffset(8)+metaOffset(8)+startNanos(8). */
    private static long readChunkStartTimeNanos(Path jfrFile) throws Exception {
        return readHeaderLong(jfrFile, 32);
    }

    /** Chunk duration in nanos at offset 40. */
    private static long readChunkDurationNanos(Path jfrFile) throws Exception {
        return readHeaderLong(jfrFile, 40);
    }

    private static long readHeaderLong(Path jfrFile, int offset) throws Exception {
        try (var raf = new java.io.RandomAccessFile(jfrFile.toFile(), "r")) {
            raf.seek(offset);
            return raf.readLong();
        }
    }

    private static void writeHeaderLong(Path jfrFile, int offset, long value) throws Exception {
        try (var raf = new java.io.RandomAccessFile(jfrFile.toFile(), "rw")) {
            raf.seek(offset);
            raf.writeLong(value);
        }
    }

    /** Recording span derived from event timestamps in the file. */
    private static Duration eventSpan(Path jfrFile) throws Exception {
        var events = RecordingFile.readAllEvents(jfrFile);
        Instant min =
                events.stream()
                        .map(e -> e.getStartTime())
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        Instant max =
                events.stream()
                        .map(e -> e.getEndTime())
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
        return Duration.between(min, max);
    }

    @Name("StreamTestEvent")
    @Label("Stream Test Event")
    static class StreamTestEvent extends Event {
        int number;

        StreamTestEvent(int number) {
            this.number = number;
        }
    }

    /**
     * Reproduces the agent recording path: events are streamed through {@link
     * BasicJFRWriter#processEvent} (not {@code processJFRFile(Path)}), so the universe start time is
     * the first event's start and the written {@code lastStartTimeNanos} equals the start time.
     * After inflating, the chunk header must still carry a non-zero, realistic duration.
     */
    @Test
    public void inflatedChunkDurationCorrectForStreamedRecording() throws Exception {
        ByteArrayOutputStream condensed = new ByteArrayOutputStream();
        Instant firstStart;
        Instant lastEnd;
        java.util.concurrent.atomic.AtomicReference<Instant> firstRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Instant> lastRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();

        try (CondensedOutputStream out =
                new CondensedOutputStream(condensed, StartMessage.DEFAULT)) {
            BasicJFRWriter writer = new BasicJFRWriter(out, Configuration.DEFAULT);
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("StreamTestEvent");
                rs.onEvent(
                        "StreamTestEvent",
                        event -> {
                            writer.processEvent(event);
                            if (firstRef.get() == null) {
                                firstRef.set(event.getStartTime());
                            }
                            lastRef.set(event.getEndTime());
                            if (count.incrementAndGet() == 3) {
                                rs.close();
                            }
                        });
                rs.startAsync();
                new StreamTestEvent(0).commit();
                Thread.sleep(200);
                new StreamTestEvent(1).commit();
                Thread.sleep(200);
                new StreamTestEvent(2).commit();
                rs.awaitTermination();
            }
            writer.close();
        }
        firstStart = firstRef.get();
        lastEnd = lastRef.get();
        Duration observedSpan = Duration.between(firstStart, lastEnd);

        // Inflate the streamed CJFR to a real JFR file.
        Path inflated = Files.createTempFile("inflated-stream", ".jfr");
        try (var in = new CondensedInputStream(condensed.toByteArray())) {
            WritingJFRReader.toJFRFile(new BasicJFRReader(in), inflated);
        }

        long startNanos = readChunkStartTimeNanos(inflated);
        Instant inflatedStart = Instant.ofEpochSecond(0, startNanos);
        long durationNanos = readChunkDurationNanos(inflated);

        // Start time must be the real recording start, not near epoch (1970).
        assertThat(inflatedStart.getEpochSecond())
                .describedAs("inflated chunk start should be a real timestamp: %s", inflatedStart)
                .isGreaterThan(Instant.parse("2020-01-01T00:00:00Z").getEpochSecond());
        assertThat(Math.abs(firstStart.getEpochSecond() - inflatedStart.getEpochSecond()))
                .describedAs(
                        "chunk start: firstEvent=%s inflated=%s", firstStart, inflatedStart)
                .isLessThanOrEqualTo(1L);

        // Duration must reflect the ~400ms span between the first and last event, not near zero.
        assertThat(durationNanos)
                .describedAs(
                        "inflated chunk duration (%d ns) should cover the observed span %s",
                        durationNanos, observedSpan)
                .isGreaterThanOrEqualTo(observedSpan.toNanos());
        assertThat(durationNanos)
                .describedAs("inflated chunk duration should not be wildly larger than the span")
                .isLessThanOrEqualTo(observedSpan.toNanos() + Duration.ofSeconds(2).toNanos());

        Files.deleteIfExists(inflated);
    }

    /**
     * The chunk-header duration must be written without relying on reflective mutation of JMC's
     * final {@code RecordingImpl.duration} field. Reflective final-field mutation is deprecated on
     * JDK 26 and will be blocked in a future release; when it fails the old code silently produced a
     * near-zero duration. This exercises the reflection-free header patch directly.
     */
    @Test
    public void patchChunkHeaderDurationWritesOffset40() throws Exception {
        // Build a minimal but valid JFR file via inflate, then zero out its duration header
        // to simulate the "reflection failed / duration never set" state, and verify the
        // reflection-free patch restores it.
        Path jfr = Files.createTempFile("patch-target", ".jfr");
        try (var rs = new RecordingStream()) {
            rs.enable("StreamTestEvent");
            rs.startAsync();
            new StreamTestEvent(0).commit();
            new StreamTestEvent(1).commit();
            Thread.sleep(100);
            rs.dump(jfr);
        }

        // Corrupt the duration header (offset 40) to 1 ns, the exact bogus value the old
        // silent-fallback produced.
        writeHeaderLong(jfr, 40, 1L);
        assertThat(readChunkDurationNanos(jfr)).isEqualTo(1L);

        long expectedDuration = Duration.ofMillis(1234).toNanos();
        WritingJFRReader.patchChunkHeaderDuration(jfr, expectedDuration);

        assertThat(readChunkDurationNanos(jfr))
                .describedAs("duration header must be patched without reflection")
                .isEqualTo(expectedDuration);

        Files.deleteIfExists(jfr);
    }

    /**
     * Guards against the silent-fallback regression at the {@link WritingJFRReader#toJFRFile}
     * level: inflating a real streamed recording through {@code toJFRFile} must leave the chunk
     * header carrying a realistic duration, never JMC's 1 ns construction-time placeholder. The old
     * reflective path silently produced the placeholder when reflection failed; this test fails if
     * the reflection-free patch is ever skipped in the {@code toJFRFile} pipeline.
     */
    @Test
    public void inflatedHeaderDurationEqualsActualDurationAndIsNotPlaceholder() throws Exception {
        ByteArrayOutputStream condensed = new ByteArrayOutputStream();

        try (CondensedOutputStream out =
                new CondensedOutputStream(condensed, StartMessage.DEFAULT)) {
            BasicJFRWriter writer = new BasicJFRWriter(out, Configuration.DEFAULT);
            java.util.concurrent.atomic.AtomicInteger count =
                    new java.util.concurrent.atomic.AtomicInteger();
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("StreamTestEvent");
                rs.onEvent(
                        "StreamTestEvent",
                        event -> {
                            writer.processEvent(event);
                            if (count.incrementAndGet() == 3) {
                                rs.close();
                            }
                        });
                rs.startAsync();
                new StreamTestEvent(0).commit();
                Thread.sleep(200);
                new StreamTestEvent(1).commit();
                Thread.sleep(200);
                new StreamTestEvent(2).commit();
                rs.awaitTermination();
            }
            writer.close();
        }

        Path inflated = Files.createTempFile("inflated-actual", ".jfr");
        try (var in = new CondensedInputStream(condensed.toByteArray())) {
            WritingJFRReader.toJFRFile(new BasicJFRReader(in), inflated);
        }

        long headerDurationNanos = readChunkDurationNanos(inflated);
        assertThat(headerDurationNanos)
                .describedAs("header duration must not be the 1 ns placeholder after toJFRFile")
                .isGreaterThan(1L);
        // The three events span ~400ms; the patched duration should reflect that, not a stale value.
        assertThat(headerDurationNanos)
                .describedAs("header duration should cover the ~400ms event span")
                .isGreaterThanOrEqualTo(Duration.ofMillis(300).toNanos());

        Files.deleteIfExists(inflated);
    }

    /**
     * {@link WritingJFRReader#patchChunkHeaderDuration} must walk every chunk in a multi-chunk JFR
     * file (following each header's chunkSize at offset 8) and patch the duration of all of them,
     * not just the first.
     */
    @Test
    public void patchChunkHeaderDurationPatchesAllChunks() throws Exception {
        // Build a synthetic 3-chunk file: each chunk is a valid header with a known chunkSize and a
        // bogus 1 ns duration. We don't need real event bodies — patchChunkHeaderDuration only reads
        // magic + chunkSize and writes offset 40.
        int chunkSize = 64; // >= header duration offset + 8, and large enough to advance past
        int numChunks = 3;
        Path jfr = Files.createTempFile("multichunk", ".jfr");
        try (var raf = new java.io.RandomAccessFile(jfr.toFile(), "rw")) {
            for (int i = 0; i < numChunks; i++) {
                long base = (long) i * chunkSize;
                raf.seek(base);
                raf.write(new byte[] {'F', 'L', 'R', 0}); // magic
                raf.writeShort(2); // major
                raf.writeShort(1); // minor
                raf.seek(base + 8);
                raf.writeLong(chunkSize); // chunkSize @ offset 8
                raf.seek(base + 40);
                raf.writeLong(1L); // bogus duration placeholder
                // pad the rest of the chunk with zeros
                raf.seek(base + chunkSize - 1);
                raf.writeByte(0);
            }
        }

        // Sanity: every chunk starts with the 1 ns placeholder.
        for (int i = 0; i < numChunks; i++) {
            assertThat(readHeaderLong(jfr, i * chunkSize + 40)).isEqualTo(1L);
        }

        long expected = Duration.ofMillis(5000).toNanos();
        WritingJFRReader.patchChunkHeaderDuration(jfr, expected);

        for (int i = 0; i < numChunks; i++) {
            assertThat(readHeaderLong(jfr, i * chunkSize + 40))
                    .describedAs("chunk %d duration must be patched", i)
                    .isEqualTo(expected);
        }

        Files.deleteIfExists(jfr);
    }

    @Test
    public void inflatedChunkStartAndDurationMatchOriginal() throws Exception {
        Path originalJfr = CommandTestUtil.getSampleJFRFile();

        long originalStartNanos = readChunkStartTimeNanos(originalJfr);
        Instant originalStart = Instant.ofEpochSecond(0, originalStartNanos);
        Duration originalSpan = eventSpan(originalJfr);

        new CommandExecuter("condense", "T/sample.jfr", "T/test.cjfr")
                .withFiles(originalJfr)
                .checkNoError()
                .check(
                        (r, map) ->
                                new CommandExecuter("inflate", "T/test.cjfr", "T/inflated.jfr")
                                        .withFiles(map.get("test.cjfr"))
                                        .checkNoError()
                                        .check(
                                                (r2, m2) -> {
                                                    Path inflated = m2.get("inflated.jfr");
                                                    assertThat(inflated).exists();

                                                    long inflatedStartNanos =
                                                            readChunkStartTimeNanos(inflated);
                                                    Instant inflatedStart =
                                                            Instant.ofEpochSecond(
                                                                    0, inflatedStartNanos);
                                                    long inflatedDurationNanos =
                                                            readChunkDurationNanos(inflated);

                                                    // Start time must match the original recording
                                                    // start within 1 second.
                                                    assertThat(
                                                                    Math.abs(
                                                                            originalStart
                                                                                            .getEpochSecond()
                                                                                    - inflatedStart
                                                                                            .getEpochSecond()))
                                                            .describedAs(
                                                                    "chunk start: original=%s"
                                                                            + " inflated=%s",
                                                                    originalStart, inflatedStart)
                                                            .isLessThanOrEqualTo(1L);

                                                    // Duration must be at least the observed event
                                                    // span and not wildly larger.
                                                    assertThat(inflatedDurationNanos)
                                                            .describedAs("inflated duration nanos")
                                                            .isGreaterThanOrEqualTo(
                                                                    originalSpan.toNanos());
                                                    assertThat(inflatedDurationNanos)
                                                            .describedAs(
                                                                    "inflated duration should be"
                                                                        + " within 5s of event span"
                                                                        + " %s",
                                                                    originalSpan)
                                                            .isLessThanOrEqualTo(
                                                                    originalSpan.toNanos()
                                                                            + Duration.ofSeconds(5)
                                                                                    .toNanos());
                                                })
                                        .run())
                .run();
    }
}
