package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Bug 251: Inflated JFR files have wrong chunk header timestamps.
 *
 * <p>The inflated JFR chunk header has startTime = epoch (1970-01-01) and a wildly wrong duration,
 * because WritingJFRReader.initRecording() hardcodes withTimestamp(1) instead of using the actual
 * recording start time from the CJFR metadata.
 *
 * <p>While the event-level timestamps are correct, the chunk-level metadata is wrong, which causes
 * tools like {@code jfr summary} to show incorrect start time and duration.
 */
@InflaterRelated
public class ChunkHeaderTest {

    /**
     * Read the chunk start time (epoch nanos) from a JFR file's binary header. JFR chunk header
     * layout: magic(4) + version(4) + chunkSize(8) + cpOffset(8) + metaOffset(8) +
     * startTimeNanos(8) + durationNanos(8)
     */
    private static long readChunkStartTimeNanos(Path jfrFile) throws Exception {
        byte[] header = new byte[48]; // need 32 + 8 + 8 bytes
        try (var is = Files.newInputStream(jfrFile)) {
            int read = is.read(header);
            assertThat(read).isEqualTo(48);
        }
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        // Skip magic(4) + version(4) + chunkSize(8) + cpOffset(8) + metaOffset(8) = 32
        return buf.getLong(32);
    }

    /** Read the chunk duration (nanos) from byte offset 40. */
    private static long readChunkDurationNanos(Path jfrFile) throws Exception {
        byte[] header = new byte[48];
        try (var is = Files.newInputStream(jfrFile)) {
            int read = is.read(header);
            assertThat(read).isEqualTo(48);
        }
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        return buf.getLong(40);
    }

    @Test
    public void testInflatedJFRHasCorrectChunkStartTime() throws Exception {
        Path profileJfr = Path.of("profile.jfr");
        if (!Files.exists(profileJfr)) {
            System.err.println("Skipping: profile.jfr not found");
            return;
        }

        // Read the original JFR chunk header start time
        long originalStartNanos = readChunkStartTimeNanos(profileJfr);
        Instant originalStart = Instant.ofEpochSecond(0, originalStartNanos);
        assertThat(originalStart.getEpochSecond())
                .describedAs("Original JFR should have a real start time")
                .isGreaterThan(Instant.parse("2020-01-01T00:00:00Z").getEpochSecond());

        new CommandExecuter("condense", "T/profile.jfr", "T/test.cjfr")
                .withFiles(profileJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("test.cjfr");
                            new CommandExecuter("inflate", "T/test.cjfr", "T/inflated.jfr")
                                    .withFiles(map.get("test.cjfr"))
                                    .checkNoError()
                                    .check(
                                            (r2, m2) -> {
                                                Path inflatedFile = m2.get("inflated.jfr");
                                                assertThat(inflatedFile).exists();

                                                long inflatedStartNanos;
                                                try {
                                                    inflatedStartNanos =
                                                            readChunkStartTimeNanos(inflatedFile);
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                                Instant inflatedStart =
                                                        Instant.ofEpochSecond(
                                                                0, inflatedStartNanos);

                                                // Chunk start time should be a real
                                                // timestamp, not near epoch
                                                assertThat(inflatedStart.getEpochSecond())
                                                        .describedAs(
                                                                "Inflated JFR chunk start time"
                                                                    + " should not be near epoch"
                                                                    + " (1970-01-01): got %s",
                                                                inflatedStart)
                                                        .isGreaterThan(
                                                                Instant.parse(
                                                                                "2020-01-01T00:00:00Z")
                                                                        .getEpochSecond());

                                                // Should be within 60 seconds of
                                                // original
                                                long diffSeconds =
                                                        Math.abs(
                                                                originalStart.getEpochSecond()
                                                                        - inflatedStart
                                                                                .getEpochSecond());
                                                assertThat(diffSeconds)
                                                        .describedAs(
                                                                "Chunk start times should be"
                                                                        + " close: original=%s"
                                                                        + " inflated=%s",
                                                                originalStart, inflatedStart)
                                                        .isLessThan(60);

                                                // Duration should be positive and not wildly off
                                                long inflatedDurationNanos;
                                                try {
                                                    inflatedDurationNanos =
                                                            readChunkDurationNanos(inflatedFile);
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                                assertThat(inflatedDurationNanos)
                                                        .describedAs("Duration should be positive")
                                                        .isGreaterThan(0);
                                                assertThat(inflatedDurationNanos / 1_000_000_000L)
                                                        .describedAs(
                                                                "Duration should be less than"
                                                                        + " 1 hour (got %d s)",
                                                                inflatedDurationNanos
                                                                        / 1_000_000_000L)
                                                        .isLessThan(3600);
                                            })
                                    .run();
                        })
                .run();
    }
}
