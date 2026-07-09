package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards against the chunk-header corruption observed in a real condense→inflate run (colleague's
 * HA_condenser_JFR.inflated.sum): the inflated file reported Start = 1970-01-01 epoch and Duration
 * = 62287129 s, even though the source recording was a valid 60 s span.
 *
 * <p>These guards make the corruption fail loudly at the point it is introduced, instead of
 * silently emitting a JFR file with a nonsensical header that downstream tools then misinterpret.
 */
public class ChunkHeaderGuardTest {

    /**
     * Builds a minimal, single-chunk JFR file with the given start/duration nanos so we can
     * exercise {@link WritingJFRReader#patchChunkHeaderDuration} in isolation.
     *
     * <p>Layout: magic(4) 'FLR\0' + major(2) + minor(2) + chunkSize(8) + cpOffset(8) +
     * metaOffset(8) + startNanos(8) + durationNanos(8), big-endian.
     */
    private static Path writeMinimalChunk(Path dir, long startNanos, long durationNanos)
            throws IOException {
        Path f = dir.resolve("chunk.jfr");
        int headerSize = 48;
        try (var raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.write(new byte[] {'F', 'L', 'R', 0});
            raf.writeShort(2); // major
            raf.writeShort(0); // minor
            raf.writeLong(headerSize); // chunkSize == header only (single chunk)
            raf.writeLong(headerSize); // cpOffset
            raf.writeLong(headerSize); // metaOffset
            raf.writeLong(startNanos); // startNanos @ 32
            raf.writeLong(durationNanos); // durationNanos @ 40
        }
        return f;
    }

    private static long readDuration(Path f) throws IOException {
        try (var raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(40);
            return raf.readLong();
        }
    }

    @Test
    public void testPatchRejectsNonPositiveDuration(@TempDir Path dir) throws IOException {
        Path f = writeMinimalChunk(dir, 1_000_000_000L, 60_000_000_000L);
        assertThrows(
                IllegalArgumentException.class,
                () -> WritingJFRReader.patchChunkHeaderDuration(f, 0),
                "Patching a zero duration into the chunk header should be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> WritingJFRReader.patchChunkHeaderDuration(f, -5),
                "Patching a negative duration into the chunk header should be rejected");
    }

    @Test
    public void testPatchRejectsAbsurdlyLargeDuration(@TempDir Path dir) throws IOException {
        Path f = writeMinimalChunk(dir, 1_000_000_000L, 60_000_000_000L);
        // 62287129 s (the observed corruption) is ~2 years; no single JFR chunk spans that.
        long absurd = 62_287_129L * 1_000_000_000L;
        assertThrows(
                IllegalArgumentException.class,
                () -> WritingJFRReader.patchChunkHeaderDuration(f, absurd),
                "Patching an implausibly large duration should be rejected as corruption");
    }

    @Test
    public void testPatchAcceptsPlausibleDuration(@TempDir Path dir) throws IOException {
        Path f = writeMinimalChunk(dir, 1_000_000_000L, 1);
        long sixtySeconds = 60_000_000_000L;
        assertDoesNotThrow(() -> WritingJFRReader.patchChunkHeaderDuration(f, sixtySeconds));
        assertEquals(sixtySeconds, readDuration(f), "Plausible duration should be written through");
    }
}
