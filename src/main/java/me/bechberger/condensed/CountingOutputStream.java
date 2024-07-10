package me.bechberger.condensed;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;

class CountingOutputStream extends OutputStream {
    private final OutputStream out;
    private int count = 0;

    public CountingOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
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
    public void write(@NotNull byte @NotNull [] b) throws IOException {
        out.write(b);
        count += b.length;
    }

    @Override
    public void write(@NotNull byte @NotNull [] b, int off, int len) throws IOException {
        out.write(b, off, len);
        count += len;
    }

    public int writtenBytes() {
        return count;
    }
}
