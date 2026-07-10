package me.bechberger.condensed;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import org.jetbrains.annotations.NotNull;

class CountingOutputStream extends OutputStream {
    private final OutputStream out;
    private volatile long count = 0;
    private final CRC32 crc = new CRC32();

    public CountingOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        crc.update(b);
        count++;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void write(byte @NotNull [] b) throws IOException {
        out.write(b);
        crc.update(b);
        count += b.length;
    }

    @Override
    public void write(byte @NotNull [] b, int off, int len) throws IOException {
        out.write(b, off, len);
        crc.update(b, off, len);
        count += len;
    }

    public long writtenBytes() {
        return count;
    }

    /** CRC32 over all bytes written so far. */
    public long crc32() {
        return crc.getValue();
    }

    public void reset() {
        count = 0;
        crc.reset();
    }
}
