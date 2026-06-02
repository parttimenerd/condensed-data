package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.types.VarIntType;
import org.junit.jupiter.api.Test;

@SuppressWarnings("resource") // test streams backed by ByteArrayOutputStream don't require closing
public class CondensedOutputStreamTest {

    /**
     * Bug: writeUnsignedLong(value, 1) calls writeSingleByte(value) but does NOT return, so it
     * falls through to writeUnsignedLong(value, 1, ERROR) which writes the same byte a second time.
     *
     * <p>Expected: writing a 1-byte unsigned long should emit exactly 1 byte. Actual: it emits 2
     * bytes (duplicate write).
     */
    @Test
    public void testWriteUnsignedLongOneByteDoesNotDoubleWrite() {
        var baos = new ByteArrayOutputStream();
        var out = new CondensedOutputStream(baos);

        out.writeUnsignedLong(42, 1);

        // Should have written exactly 1 byte
        assertEquals(
                1,
                baos.size(),
                "writeUnsignedLong(42, 1) should write exactly 1 byte, but wrote " + baos.size());
        assertEquals(42, baos.toByteArray()[0] & 0xFF);
    }

    /**
     * Additional check: the value read back from a 1-byte write should round-trip correctly.
     * Because the byte is written twice, a CondensedInputStream reading back would get the wrong
     * value (consuming 2 bytes instead of 1).
     */
    @Test
    public void testWriteReadUnsignedLongOneByteRoundtrip() {
        var baos = new ByteArrayOutputStream();
        var out = new CondensedOutputStream(baos);

        // Write two separate 1-byte values
        out.writeUnsignedLong(42, 1);
        out.writeUnsignedLong(99, 1);

        // Should be 2 bytes total
        byte[] data = baos.toByteArray();
        assertEquals(
                2,
                data.length,
                "Two 1-byte writes should produce exactly 2 bytes, but got " + data.length);

        // Read them back
        var in = new CondensedInputStream(data);
        assertEquals(42, in.readUnsignedLong(1), "First value should be 42");
        assertEquals(99, in.readUnsignedLong(1), "Second value should be 99");
    }

    /**
     * Bug: writeString writes the string body directly to outputStream.write(bytes, 0,
     * bytes.length) instead of this.write(bytes), bypassing statistic.record(). This means
     * statistic.getBytes() does not count string body bytes — only the varint length prefix is
     * counted.
     *
     * <p>Expected: statistic.getBytes() == total bytes written (varint prefix + string body)
     * Actual: statistic.getBytes() == only varint prefix bytes (string body is missing)
     */
    @Test
    public void testWriteStringStatisticsIncludeStringBody() {
        var baos = new ByteArrayOutputStream();
        var out = new CondensedOutputStream(baos);
        out.enableFullStatistics();

        String testString = "Hello, World!"; // 13 bytes in UTF-8
        out.writeString(testString);

        int actualBytesWritten = baos.size();
        long statisticBytes = out.getStatistics().getBytes();

        // The varint for length 13 is 1 byte, so total = 1 + 13 = 14 bytes
        assertEquals(
                actualBytesWritten,
                statisticBytes,
                "statistic.getBytes() should match actual bytes written to stream. "
                        + "Actual stream bytes: "
                        + actualBytesWritten
                        + ", statistic reports: "
                        + statisticBytes
                        + ". The string body bytes are missing from statistics.");
    }

    /**
     * Bug: writeMessage does not call statistic.setModeAndCount(WriteMode.INSTANCE), unlike
     * writeMessageReduced which correctly does. This means instance writes via writeMessage get
     * counted as WriteMode.OTHER instead of WriteMode.INSTANCE.
     *
     * <p>Expected: After writeMessage, instance count is incremented Actual: Instance count stays
     * 0, OTHER count is used instead
     */
    @Test
    public void testWriteMessageSetsInstanceMode() {
        var baos = new ByteArrayOutputStream();
        var out = new CondensedOutputStream(baos);
        out.enableFullStatistics();

        // Create a simple type and write a message
        VarIntType intType = out.writeAndStoreType(id -> new VarIntType(id));

        // Write an instance message
        out.writeMessage(intType, 42L);

        // Get the pretty string after writing
        String afterStats = out.getStatistics().toPrettyString();

        // The instance row should show count > 0
        // Parse the "instance" row to check count
        String[] lines = afterStats.split("\n");
        String instanceLine = null;
        for (String line : lines) {
            if (line.trim().startsWith("instance")) {
                instanceLine = line;
                break;
            }
        }
        assertNotNull(instanceLine, "Should have an 'instance' line in statistics");
        // The count field is the second column (after "instance")
        String[] parts = instanceLine.trim().split("\\s+");
        int instanceCount = Integer.parseInt(parts[1]);
        assertTrue(
                instanceCount > 0,
                "Instance count should be > 0 after writeMessage, but got: "
                        + instanceCount
                        + ". writeMessage is missing statistic.setModeAndCount(WriteMode.INSTANCE)."
                        + " Full stats:\n"
                        + afterStats);
    }

    @Test
    public void testWriteFooterSentinelByteIsPresent() {
        var baos = new ByteArrayOutputStream();
        var out = new CondensedOutputStream(baos, StartMessage.DEFAULT);
        var footer = new CJFRFooter(1, 0L, 0L, 0L, Map.of(), null, null, null);
        out.writeFooter(footer);
        byte[] bytes = baos.toByteArray();
        // Find sentinel byte 7 — it should appear right before the zlib blob
        // The zlib BEST_COMPRESSION magic starts with 0x78 0xDA
        // Find the 0x78 byte and check what's before it
        int sentinelPos = -1;
        for (int i = 0; i < bytes.length - 1; i++) {
            if ((bytes[i] & 0xFF) == 7 && (bytes[i + 1] & 0xFF) == 0x78) {
                sentinelPos = i;
                break;
            }
        }
        assertTrue(sentinelPos >= 0,
            "Sentinel byte 7 should appear before zlib magic 0x78 in the output. "
            + "File bytes (last 20): " + bytesToHex(bytes, Math.max(0, bytes.length - 20), bytes.length));
        // Also verify that CondensedInputStream stops at the sentinel
        var in = new CondensedInputStream(bytes);
        while (in.readNextMessageAndProcess() != null) {}
        // If we reach here, the sentinel was detected and reading stopped cleanly
    }

    private static String bytesToHex(byte[] bytes, int from, int to) {
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(String.format("%02x ", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
