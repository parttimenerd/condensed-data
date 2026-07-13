package me.bechberger.condensed;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.function.Consumer;
import java.util.function.Function;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.stats.BasicStatistic;
import me.bechberger.condensed.stats.Statistic;
import me.bechberger.condensed.stats.WriteCause;
import me.bechberger.condensed.stats.WriteMode;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.Reductions;
import me.bechberger.condensed.types.SpecifiedType;
import me.bechberger.condensed.types.TypeCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps an {@see OutputStream} to offer additional methods for writing condensed data.
 *
 * <p>Writes data that can be read by {@see CondensedInputStream}
 */
public class CondensedOutputStream extends OutputStream {

    private final Universe universe;

    private final TypeCollection typeCollection;

    private OutputStream outputStream;
    private final CountingOutputStream underlyingCountingStream;

    private Reductions reductions = Reductions.NONE;

    private Statistic statistic = new BasicStatistic();

    private boolean closed = false;

    /** Uncompressed byte count observed at the most recent {@link #flush()} (0 = never flushed). */
    private volatile long uncompressedAtLastFlush = 0;

    public CondensedOutputStream(OutputStream outputStream, StartMessage startMessage) {
        this(outputStream, startMessage, new Universe());
    }

    public void setHashAndEqualsConfig(HashAndEqualsConfig hashAndEqualsConfig) {
        universe.setHashAndEqualsConfig(hashAndEqualsConfig);
    }

    public CondensedOutputStream(
            OutputStream outputStream, StartMessage startMessage, Universe universe) {
        this(outputStream, universe);
        try (var t = statistic.withWriteCauseContext(WriteCause.Start)) {
            writeStartString(startMessage);
        }
        if (startMessage.compression() != Compression.NONE) {
            this.outputStream =
                    startMessage
                            .compression()
                            .wrap(this.outputStream, startMessage.compressionLevel());
        }
    }

    /** Create an output stream without a start string and message, used for testing */
    CondensedOutputStream(OutputStream outputStream, Universe universe) {
        this.typeCollection = new TypeCollection();
        this.underlyingCountingStream = new CountingOutputStream(outputStream);
        // Always use a non-closing wrapper as outputStream so that close() never cascades to
        // underlyingCountingStream. writeFooter (and close() for non-footer paths) close it
        // explicitly at the right time.
        this.outputStream =
                new FilterOutputStream(underlyingCountingStream) {
                    @Override
                    public void close() {} // intentionally no-op
                };
        this.universe = universe;
    }

    CondensedOutputStream(OutputStream outputStream) {
        this(outputStream, new Universe());
    }

    public void enableFullStatistics() {
        if (statistic instanceof BasicStatistic) {
            this.statistic = new Statistic();
        }
    }

    public void setStatistics(Statistic statistic) {
        this.statistic = statistic;
    }

    private void writeStartString(StartMessage startMessage) {
        universe.setStartMessage(startMessage);
        writeString(Constants.START_STRING);
        writeUnsignedVarInt(startMessage.version());
        writeString(startMessage.generatorName());
        writeString(startMessage.generatorVersion());
        writeString(startMessage.generatorConfiguration());
        writeString(startMessage.compression().name());
        writeUnsignedVarInt(startMessage.compressionLevel().ordinal());
    }

    static byte[] useCompressed(Consumer<CondensedOutputStream> consumer) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CondensedOutputStream condensedOutputStream =
                new CondensedOutputStream(
                        outputStream, StartMessage.DEFAULT.compress(Compression.DEFAULT));
        consumer.accept(condensedOutputStream);
        condensedOutputStream.close();
        return outputStream.toByteArray();
    }

    static byte[] use(Consumer<CondensedOutputStream> consumer, boolean useStartMessage) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CondensedOutputStream condensedOutputStream =
                useStartMessage
                        ? new CondensedOutputStream(outputStream, StartMessage.DEFAULT)
                        : new CondensedOutputStream(outputStream);
        consumer.accept(condensedOutputStream);
        condensedOutputStream.close();
        return outputStream.toByteArray();
    }

    private void writeMessageType(int messageType) {
        writeUnsignedVarInt(messageType);
    }

    public synchronized <C extends CondensedType<?, ?>> C writeAndStoreType(
            Function<Integer, C> typeCreator) {
        var type = typeCollection.addType(typeCreator);
        writeType(type);
        return type;
    }

    /**
     * Writes a type specification to the stream
     *
     * <p>The type has to be present in the type collection.
     *
     * @param type the type instance to write
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void writeType(CondensedType<?, ?> type) {
        if (!typeCollection.hasType(type.getId())
                || !typeCollection.getType(type.getId()).equals(type)) {
            throw new AssertionError("Type " + type + " is not present in type collection");
        }
        try (var t = statistic.withWriteCauseContext(WriteCause.TypeSpecification)) {
            statistic.setModeAndCount(WriteMode.TYPE);
            var spec = (SpecifiedType<CondensedType<?, ?>>) (SpecifiedType) type.getSpecifiedType();
            writeMessageType(spec.id());
            spec.writeTypeSpecification(this, type);
        }
    }

    public synchronized <T, R> void writeMessage(CondensedType<T, R> type, T value) {
        try (var t = statistic.withWriteCauseContext(type)) {
            statistic.setModeAndCount(WriteMode.INSTANCE);
            writeMessageType(type.getId());
            type.writeTo(this, value);
        }
    }

    /** take care that the value matches the type if the type has a reduction */
    @SuppressWarnings("unchecked")
    public synchronized <T, R, V> void writeMessageReduced(CondensedType<T, R> type, V value) {
        try (var t = statistic.withWriteCauseContext(type)) {
            statistic.setModeAndCount(WriteMode.INSTANCE);
            writeMessageType(type.getId());
            type.writeTo(this, (T) value);
        }
    }

    /**
     * Writes a varlong to the stream.
     *
     * <p>The varlong is encoded as an unsigned long.
     *
     * @param value the value to write
     */
    public void writeUnsignedVarInt(long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
            write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        write((int) (value & 0x7F));
    }

    /**
     * Writes a varlong to the stream.
     *
     * <p>The varlong is encoded as a signed long.
     *
     * @param value the value to write
     */
    public void writeSignedVarInt(long value) {
        writeUnsignedVarInt((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a string to the stream.
     *
     * @param value string to write
     */
    public void writeString(String value) {
        writeString(value, null);
    }

    /**
     * Writes a string to the stream.
     *
     * @param value string to write
     */
    public void writeString(String value, @Nullable String encoding) {
        try (var t = statistic.withWriteCauseContext(WriteCause.String)) {
            long bytesBefore = statistic.getBytes();
            byte[] bytes;
            try {
                bytes = value.getBytes(encoding != null ? encoding : "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            writeUnsignedVarInt(bytes.length);
            if (bytes.length > 0) {
                write(bytes);
            }
            statistic.recordString(statistic.getBytes() - bytesBefore);
        }
    }

    public void writeTypeId(CondensedType<?, ?> type) {
        if (!typeCollection.containsType(type)) {
            throw new IllegalArgumentException("Type not registered: " + type);
        }
        writeUnsignedVarInt(type.getId());
    }

    public enum OverflowMode {
        /** Throw an IllegalArgumentException exception if the value is too large */
        ERROR,
        /**
         * If the value is too large, return the maximum possible value, similar with too small
         * values
         */
        SATURATE
    }

    /**
     * Internal method to write a long to the stream as a fixed width integer.
     *
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8, cut off the higher bits
     */
    private void writeLong(long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            write((int) (value & 0xFF));
            value >>>= 8;
        }
    }

    /** Returns a bitmask that can be used to mask the lower n bytes of a long. */
    private long bitMask(int bytes) {
        if (bytes == 8) return -1L;
        return (1L << (bytes * 8)) - 1;
    }

    public void writeUnsignedLong(long value, int bytes) {
        if (bytes == 1) {
            writeSingleByte(value);
            return;
        }
        writeUnsignedLong(value, bytes, OverflowMode.ERROR);
    }

    public void writeSingleByte(long value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Value " + value + " does not fit into 1 byte");
        }
        write((byte) value);
    }

    /**
     * Writes an unsigned long to the stream as a fixed width integer.
     *
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8
     * @param mode the overflow mode
     */
    public void writeUnsignedLong(long value, int bytes, OverflowMode mode) {
        if (bytes < 1 || bytes > 8) {
            throw new IllegalArgumentException("Number of bytes must be between 1 and 8");
        }
        if (bytes == 8) { // we can write every number
            writeLong(value, bytes);
        } else {
            long maxValue = bitMask(bytes);
            if (value < 0 || value > maxValue) {
                if (mode == OverflowMode.ERROR) {
                    throw new IllegalArgumentException(
                            "Value " + value + " does not fit into " + bytes + " bytes");
                } else {
                    value = Long.compareUnsigned(value, maxValue) > 0 ? maxValue : value;
                }
            }
            writeLong(value, bytes);
        }
    }

    public void writeSignedLong(long value, int bytes) {
        writeSignedLong(value, bytes, OverflowMode.ERROR);
    }

    /**
     * Writes an signed long to the stream as a fixed width integer.
     *
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8
     * @param mode the overflow and underflow mode
     */
    public void writeSignedLong(long value, int bytes, OverflowMode mode) {
        if (bytes < 1 || bytes > 8) {
            throw new IllegalArgumentException("Number of bytes must be between 1 and 8");
        }
        if (bytes == 8) { // we can write every number
            writeLong(value, bytes);
        } else {
            long maxValue = (1L << (8 * bytes - 1)) - 1;
            long minValue = -(1L << (8 * bytes - 1));
            if (value < minValue || value > maxValue) {
                if (mode == OverflowMode.ERROR) {
                    throw new IllegalArgumentException(
                            "Value " + value + " does not fit into " + bytes + " bytes");
                } else {
                    value = Math.max(minValue, Math.min(maxValue, value));
                }
            }
            writeLong(value, bytes);
        }
    }

    /** Writes a float to the stream */
    public void writeFloat(float value) {
        writeSignedLong(Float.floatToRawIntBits(value), 4, OverflowMode.ERROR);
    }

    public void writeFloat16(float value) {
        writeSignedLong(Util.floatToFp16(value), 2, OverflowMode.ERROR);
    }

    public void writeBFloat16(float value) {
        writeSignedLong(Util.floatToBf16(value), 2, OverflowMode.ERROR);
    }

    /** Writes a double to the stream */
    public void writeDouble(double value) {
        writeSignedLong(Double.doubleToRawLongBits(value), 8, OverflowMode.ERROR);
    }

    /**
     * Write (at most 8) flags in a single byte
     *
     * @param flags lowest to highest bit
     * @throws IllegalArgumentException if more than 8 flags are given
     */
    public void writeFlags(boolean... flags) {
        if (flags.length > 8) {
            throw new IllegalArgumentException("Too many flags");
        }
        byte result = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) {
                result |= (byte) (1 << i);
            }
        }
        write(result);
    }

    /** Writes the specified byte to the stream */
    @Override
    public void write(int b) {
        try {
            outputStream.write(b);
            statistic.record(1);
        } catch (IOException e) {
            throw new RIOException("Can't write byte", e);
        }
    }

    @Override
    public void write(byte @NotNull [] b) {
        try {
            statistic.record(b.length);
            outputStream.write(b);
        } catch (IOException e) {
            throw new RIOException("Can't write byte array", e);
        }
    }

    public Universe getUniverse() {
        return universe;
    }

    public Statistic getStatistics() {
        return statistic;
    }

    public TypeCollection getTypeCollection() {
        return typeCollection;
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            outputStream.close(); // flushes/closes the compressor (or no-op wrapper for NONE)
            underlyingCountingStream.close(); // closes the real sink
        } catch (IOException e) {
            throw new RIOException("Can't close stream", e);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void setReductions(Reductions reductions) {
        this.reductions = reductions;
    }

    public Reductions getReductions() {
        return reductions;
    }

    /**
     * Real on-disk size in bytes: the count of bytes already flushed through the compressor to the
     * underlying stream. With block-buffered compression (LZ4FRAMED) this stays near zero until a
     * block completes or {@link #flush()} is called, so it under-reports the logical size
     * mid-block. Use {@link #estimateOnDiskSize()} for a size that grows with the data.
     */
    public long estimateSize() {
        return underlyingCountingStream.writtenBytes();
    }

    /** Total uncompressed bytes recorded so far (pre-compression logical size). */
    public long getUncompressedBytes() {
        return statistic.getBytes();
    }

    /**
     * Predicts the on-disk (compressed) size without forcing a flush, by scaling the uncompressed
     * byte count by the compression ratio observed at the last flush ({@code flushedBytes /
     * uncompressedAtLastFlush}). Before the first flush the ratio is unknown, so we assume 1.0
     * (incompressible) — a conservative over-estimate that avoids under-rotating. Callers that need
     * an exact number near a boundary should {@link #flush()} first, then read {@link
     * #estimateSize()}.
     */
    public long estimateOnDiskSize() {
        long uncompressed = statistic.getBytes();
        long flushed = underlyingCountingStream.writtenBytes();
        if (uncompressedAtLastFlush <= 0 || flushed <= 0) {
            // No flush observed yet — assume incompressible (ratio 1.0).
            return uncompressed;
        }
        double ratio = (double) flushed / (double) uncompressedAtLastFlush;
        return Math.round(uncompressed * ratio);
    }

    /**
     * Flushes buffered bytes through the compression layer to the underlying stream so {@link
     * #estimateSize()} reflects the real on-disk size, and refreshes the observed compression ratio
     * used by {@link #estimateOnDiskSize()}. No-op once closed.
     */
    @Override
    public void flush() {
        if (closed) {
            return;
        }
        try {
            outputStream.flush();
        } catch (IOException e) {
            throw new RIOException("Can't flush compression stream", e);
        }
        uncompressedAtLastFlush = statistic.getBytes();
    }

    /** Raw, pre-compression sink. Only safe to use after close() has flushed the compressor. */
    public CountingOutputStream getUnderlyingCountingStream() {
        return underlyingCountingStream;
    }

    /**
     * Closes the compression layer (if not already closed), then writes a FOOTER_TYPE_ID varint
     * sentinel followed by the zlib-compressed footer blob and a 4-byte little-endian length to the
     * raw underlying stream. Must be called at most once and must be the last write operation.
     *
     * <p>The FOOTER_TYPE_ID sentinel lets {@link CondensedInputStream} stop reading at this point
     * even when there is no compression (NONE), preventing it from mis-parsing footer bytes as
     * event data.
     */
    public synchronized void writeFooter(CJFRFooter footer) {
        if (!closed) {
            closed = true;
            try {
                outputStream.close(); // flush/close compressor only; underlyingCountingStream stays
                // open
            } catch (IOException e) {
                throw new RIOException("Can't close compression stream before writing footer", e);
            }
        }
        // Snapshot the CRC over [0, footerStart): the compressor is closed above, so
        // underlyingCountingStream has received exactly the start header + compressed main stream.
        long mainStreamCrc = underlyingCountingStream.crc32();
        byte[] zlibBytes = footer.withMainStreamCrc32(mainStreamCrc).toCompressedBytes();
        int len = zlibBytes.length;
        try {
            underlyingCountingStream.write(CJFRFooter.FOOTER_TYPE_ID);
            underlyingCountingStream.write(zlibBytes);
            underlyingCountingStream.write(len & 0xFF);
            underlyingCountingStream.write((len >>> 8) & 0xFF);
            underlyingCountingStream.write((len >>> 16) & 0xFF);
            underlyingCountingStream.write((len >>> 24) & 0xFF);
            underlyingCountingStream.flush();
            underlyingCountingStream.close();
        } catch (IOException e) {
            throw new RIOException("Failed to write footer", e);
        }
    }
}
