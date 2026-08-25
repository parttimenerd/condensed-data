package me.bechberger.condensed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import me.bechberger.femtolz4.LZ4;
import me.bechberger.femtolz4.LZ4FrameInputStream;
import me.bechberger.femtolz4.LZ4FrameOutputStream;

public enum Compression {
    NONE(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level) {
                    return out;
                }

                @Override
                public InputStream wrap(InputStream in) {
                    return in;
                }
            }),
    GZIP(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    return new ConfigurableGZIPOutputStream(
                            out,
                            switch (level) {
                                case FAST -> Deflater.BEST_SPEED;
                                case MEDIUM -> Deflater.DEFAULT_COMPRESSION;
                                case HIGH_COMPRESSION, MAX_COMPRESSION -> Deflater.BEST_COMPRESSION;
                            });
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new GZIPInputStream(in);
                }
            }),
    LZ4FRAMED(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    var compressor =
                            switch (level) {
                                case FAST -> LZ4.compressor(LZ4.LEVEL_FAST);
                                case MEDIUM, HIGH_COMPRESSION -> LZ4.compressor(8);
                                case MAX_COMPRESSION -> LZ4.compressor(LZ4.LEVEL_DEFAULT);
                            };
                    return new LZ4FrameOutputStream(out, compressor);
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new LZ4FrameInputStream(in, true);
                }
            }),
    ;

    public interface CompressionFactory {
        OutputStream wrap(OutputStream out, CompressionLevel level) throws IOException;

        InputStream wrap(InputStream in) throws IOException;
    }

    public enum CompressionLevel {
        FAST,
        MEDIUM,
        HIGH_COMPRESSION,
        MAX_COMPRESSION
    }

    private static class ConfigurableGZIPOutputStream extends GZIPOutputStream {
        public ConfigurableGZIPOutputStream(OutputStream out, int level) throws IOException {
            super(out);
            def.setLevel(level);
        }
    }

    public static final Compression DEFAULT = LZ4FRAMED;

    private final CompressionFactory factory;

    Compression(CompressionFactory factory) {
        this.factory = factory;
    }

    public OutputStream wrap(OutputStream out, CompressionLevel level) {
        try {
            return factory.wrap(out, level);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public InputStream wrap(InputStream in) {
        try {
            return factory.wrap(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
