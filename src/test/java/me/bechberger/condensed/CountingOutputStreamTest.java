package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class CountingOutputStreamTest {

    @Test
    public void testBasicCounting() throws IOException {
        var baos = new ByteArrayOutputStream();
        var cos = new CountingOutputStream(baos);
        cos.write(42);
        cos.write(new byte[] {1, 2, 3});
        cos.write(new byte[] {10, 20, 30, 40, 50}, 1, 3);
        assertEquals(7, cos.writtenBytes());
    }

    @Test
    public void testEmptyStream() throws IOException {
        var baos = new ByteArrayOutputStream();
        var cos = new CountingOutputStream(baos);
        assertEquals(0, cos.writtenBytes());
    }

    /**
     * Bug: count field is declared as int, so writtenBytes() overflows for >2GB of data.
     * writtenBytes() should return long for correctness on large files.
     *
     * <p>We can't actually write 2GB in a unit test, but we can demonstrate the type limitation:
     * after writing Integer.MAX_VALUE + 1 bytes worth of data, the count wraps negative.
     */
    @Test
    public void testOverflowBeyond2GB() throws IOException {
        var baos =
                new ByteArrayOutputStream(0) {
                    @Override
                    public void write(byte[] b, int off, int len) {
                        // no-op to avoid OOM — we just want to track the count
                    }

                    @Override
                    public void write(byte[] b) {
                        // no-op
                    }

                    @Override
                    public void write(int b) {
                        // no-op
                    }
                };
        var cos = new CountingOutputStream(baos);
        // write chunks totaling just over Integer.MAX_VALUE
        byte[] chunk = new byte[1024 * 1024]; // 1MB
        long totalWritten = 0;
        while (totalWritten <= Integer.MAX_VALUE) {
            cos.write(chunk);
            totalWritten += chunk.length;
        }
        // writtenBytes() returns int, so it wraps negative after 2GB
        // This test asserts that it SHOULD be positive (i.e., return a long)
        assertTrue(
                cos.writtenBytes() > 0,
                "writtenBytes() should be positive after writing "
                        + totalWritten
                        + " bytes, "
                        + "but int overflow caused it to be "
                        + cos.writtenBytes());
    }
}
