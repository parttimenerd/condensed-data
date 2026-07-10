package me.bechberger.condensed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.RIOException.NoStartStringException;
import me.bechberger.condensed.RIOException.UnknownCompressionException;
import me.bechberger.condensed.RIOException.UnsupportedFormatVersionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StreamErrorTest {

    @Test
    public void testStreamHasNoStartMessage() {
        try (CondensedInputStream in = new CondensedInputStream(new byte[] {1})) {
            Assertions.assertThrows(NoStartStringException.class, in::readNextMessageAndProcess);
        }
    }

    @Test
    public void testBrokenStartMessage() {
        try (CondensedInputStream in = new CondensedInputStream(new byte[] {1, 2, 3, 4})) {
            Assertions.assertThrows(NoStartStringException.class, in::readNextMessageAndProcess);
        }
    }

    @Test
    public void testStreamEndEarly() {
        try (CondensedInputStream in = new CondensedInputStream(new byte[] {1})) {
            Assertions.assertThrows(RIOException.class, () -> in.readUnsignedLong(8));
        }
    }

    /** Mirror of the on-disk start-header encoding, so we can craft malformed headers. */
    private static void writeVarInt(ByteArrayOutputStream out, long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    private static void writeStr(ByteArrayOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    /**
     * Build a start header with the given version + compression name. Layout mirrors {@link
     * CondensedOutputStream#writeStartString}: START_STRING, version, generatorName,
     * generatorVersion, generatorConfiguration, compression name, compressionLevel ordinal.
     */
    private static byte[] header(int version, String compressionName) throws IOException {
        var out = new ByteArrayOutputStream();
        writeStr(out, Constants.START_STRING);
        writeVarInt(out, version);
        writeStr(out, "gen");
        writeStr(out, "genver");
        writeStr(out, "cfg");
        writeStr(out, compressionName);
        writeVarInt(out, Compression.CompressionLevel.HIGH_COMPRESSION.ordinal());
        return out.toByteArray();
    }

    @Test
    public void testRejectsFutureFormatVersion() throws IOException {
        byte[] bytes = header(Constants.VERSION + 1, Compression.NONE.name());
        try (CondensedInputStream in = new CondensedInputStream(bytes)) {
            Assertions.assertThrows(
                    UnsupportedFormatVersionException.class, in::readNextMessageAndProcess);
        }
    }

    @Test
    public void testRejectsUnknownCompression() throws IOException {
        byte[] bytes = header(Constants.VERSION, "BOGUS");
        try (CondensedInputStream in = new CondensedInputStream(bytes)) {
            Assertions.assertThrows(
                    UnknownCompressionException.class, in::readNextMessageAndProcess);
        }
    }

    @Test
    public void testAcceptsCurrentVersion() {
        // A genuine, fully round-tripped stream at the current version must read cleanly.
        byte[] bytes = CondensedOutputStream.useCompressed(out -> {});
        try (CondensedInputStream in = new CondensedInputStream(bytes)) {
            while (in.readNextMessageAndProcess() != null) {
                // drain
            }
        }
    }

    @Test
    public void testCompressionLevelRoundTrips() {
        var start =
                StartMessage.DEFAULT
                        .compress(Compression.LZ4FRAMED)
                        .withCompressionLevel(Compression.CompressionLevel.MAX_COMPRESSION);
        var baos = new ByteArrayOutputStream();
        try (var out = new CondensedOutputStream(baos, start)) {
            // nothing to write; the header carries the level
        }
        try (CondensedInputStream in = new CondensedInputStream(baos.toByteArray())) {
            in.readNextMessageAndProcess();
            var read = in.getUniverse().getStartMessage();
            Assertions.assertNotNull(read);
            Assertions.assertEquals(
                    Compression.CompressionLevel.MAX_COMPRESSION, read.compressionLevel());
        }
    }
}
