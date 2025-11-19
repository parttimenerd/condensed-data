package me.bechberger.condensed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

public enum Compression {
    NONE(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    return out;
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
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
    XZ(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    int preset =
                            switch (level) {
                                case FAST -> 1;
                                case MEDIUM -> 6;
                                case HIGH_COMPRESSION -> 8;
                                case MAX_COMPRESSION -> 9;
                            };
                    return new XZCompressorOutputStream(out, preset);
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new XZCompressorInputStream(in);
                }
            }),
    BZIP2(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    int blockSize =
                            switch (level) {
                                case FAST -> 1; // 100k block size
                                case MEDIUM -> 5; // 500k block size
                                case HIGH_COMPRESSION -> 7; // 700k block size
                                case MAX_COMPRESSION -> 9; // 900k block size (max)
                            };
                    return new BZip2CompressorOutputStream(out, blockSize);
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new BZip2CompressorInputStream(in);
                }
            }),
    ZSTD(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    int compressionLevel =
                            switch (level) {
                                case FAST -> 1;
                                case MEDIUM -> 3;
                                case HIGH_COMPRESSION -> 19;
                                case MAX_COMPRESSION -> 22; // max level
                            };
                    return ZstdCompressorOutputStream.builder()
                            .setOutputStream(out)
                            .setLevel(compressionLevel)
                            .get();
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new ZstdCompressorInputStream(in);
                }
            });

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

    public static final Compression DEFAULT = XZ;

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