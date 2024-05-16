package me.bechberger.condensed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE;
import net.jpountz.lz4.LZ4FrameOutputStream.FLG.Bits;
import net.jpountz.xxhash.XXHashFactory;

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
                                case HIGH_COMPRESSION -> Deflater.BEST_COMPRESSION;
                                case MAX_COMPRESSION -> Deflater.BEST_COMPRESSION;
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
                                case MEDIUM -> LZ4Factory.fastestInstance().highCompressor();
                                case HIGH_COMPRESSION ->
                                        LZ4Factory.fastestInstance().highCompressor();
                                case MAX_COMPRESSION ->
                                        LZ4Factory.fastestInstance().highCompressor(17);
                            };
                    return new LZ4FrameOutputStream(
                            out,
                            BLOCKSIZE.SIZE_4MB,
                            -1,
                            compressor,
                            XXHashFactory.fastestInstance().hash32(),
                            Bits.BLOCK_INDEPENDENCE);
                }

                @Override
                public InputStream wrap(InputStream in) throws IOException {
                    return new LZ4FrameInputStream(in);
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
