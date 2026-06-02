package me.bechberger.condensed;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.xxhash.XXHashFactory;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

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
                                case FAST -> LZ4Factory.fastestInstance().fastCompressor();
                                case MEDIUM, HIGH_COMPRESSION ->
                                        LZ4Factory.fastestInstance().highCompressor();
                                case MAX_COMPRESSION ->
                                        LZ4Factory.fastestInstance().highCompressor(17);
                            };
                    return new LZ4FrameOutputStream(
                            out,
                            LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB,
                            -1,
                            compressor,
                            XXHashFactory.fastestInstance().hash32(),
                            LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE);
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new LZ4FrameInputStream(in);
                }
            }),
    ZSTD(
            new CompressionFactory() {
                @Override
                public OutputStream wrap(OutputStream out, CompressionLevel level)
                        throws IOException {
                    int zstdLevel =
                            switch (level) {
                                case FAST -> 1;
                                case MEDIUM -> 3;
                                case HIGH_COMPRESSION -> 9;
                                case MAX_COMPRESSION -> 19;
                            };
                    return new ZstdOutputStream(out, zstdLevel);
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new java.io.BufferedInputStream(new ZstdInputStream(in), 65536);
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
                                case MEDIUM -> 3;
                                case HIGH_COMPRESSION -> 6;
                                case MAX_COMPRESSION -> 9;
                            };
                    return new XZOutputStream(out, new LZMA2Options(preset));
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new XZInputStream(in);
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

    public static final Compression DEFAULT = ZSTD;

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
