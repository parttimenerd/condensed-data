package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.RIOException.IntegrityCheckException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the whole-file CRC32 integrity check: the footer stores a CRC32 over the on-disk bytes
 * {@code [0, footerStart)} (start header + compressed main stream), and {@link
 * CJFRFooterReader#verify(Path)} recomputes and compares it.
 */
public class IntegrityTest {

    private static Path writeCjfr(Path dir, Compression compression) throws Exception {
        var baos = new ByteArrayOutputStream();
        var out = new CondensedOutputStream(baos, StartMessage.DEFAULT.compress(compression));
        // Write some data so the main stream is non-trivial.
        for (int i = 0; i < 100; i++) {
            out.writeString("event-" + i);
        }
        var footer = new CJFRFooter(1, 100L, 0L, 0L, Map.of("x", 1L), null, null, null, 0L);
        out.writeFooter(footer);
        Path f = dir.resolve("data-" + compression.name() + ".cjfr");
        Files.write(f, baos.toByteArray());
        return f;
    }

    @Test
    public void testCorruptedMainStreamByteFailsCrc(@TempDir Path dir) throws Exception {
        Path f = writeCjfr(dir, Compression.NONE);
        byte[] bytes = Files.readAllBytes(f);
        // Flip a byte early in the file (inside the main stream, well before the footer).
        bytes[20] ^= 0xFF;
        Files.write(f, bytes);
        assertThrows(IntegrityCheckException.class, () -> CJFRFooterReader.verify(f));
    }

    @Test
    public void testVerifyPassesForIntactFile(@TempDir Path dir) throws Exception {
        Path f = writeCjfr(dir, Compression.LZ4FRAMED);
        assertDoesNotThrow(() -> CJFRFooterReader.verify(f));
    }

    @ParameterizedTest
    @EnumSource(Compression.class)
    public void testCrcBoundaryAllCompressions(Compression compression) throws Exception {
        Path dir = Files.createTempDirectory("integrity");
        Path f = writeCjfr(dir, compression);
        // Recompute CRC over [0, footerStart) independently and compare to the stored footer value.
        var footerOpt = CJFRFooterReader.tryRead(f);
        assertTrue(footerOpt.isPresent(), "footer must be present for " + compression);
        long stored = footerOpt.get().mainStreamCrc32();

        byte[] all = Files.readAllBytes(f);
        long footerStart = CJFRFooterReader.footerStart(f).orElseThrow();
        CRC32 crc = new CRC32();
        crc.update(all, 0, (int) footerStart);
        assertEquals(
                crc.getValue(),
                stored,
                "stored CRC must equal a fresh CRC over [0, footerStart) for " + compression);
    }

    @Test
    public void testFooterAbsentSkipsVerify(@TempDir Path dir) throws Exception {
        // A stream without a footer: verify must be a no-op, not a failure.
        var baos = new ByteArrayOutputStream();
        try (var out = new CondensedOutputStream(baos, StartMessage.DEFAULT)) {
            out.writeString("hello");
        }
        Path f = dir.resolve("nofooter.cjfr");
        Files.write(f, baos.toByteArray());
        assertDoesNotThrow(() -> CJFRFooterReader.verify(f));
    }
}
